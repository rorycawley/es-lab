(ns lab29.telemetry-test
  "What the telemetry pipeline is asserted to produce.

  These are not architecture tests. Each one names a claim this lab's README
  makes, and each would fail if the claim stopped being true."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.fixture :as fixture]
            [lab29.ordering.api :as ordering]
            [lab29.postgres :as postgres]
            [lab29.recorder :as recorder]
            [lab29.system :as system]
            [next.jdbc :as jdbc]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def order-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")

(defn- delivered-to [summary consumer]
  (filterv #(= consumer (:consumer %)) (:delivered summary)))

(defn- change-vanilla! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- place-order-request [order-id quantity]
  {:order-id order-id
   :correlation-id (random-uuid)
   :product-id vanilla
   :quantity quantity
   :customer-email "ada@example.com"
   :payment-method "pm_card_visa"})

;; ---------------------------------------------------------------------------
;; Logs
;; ---------------------------------------------------------------------------

(deftest a-log-carries-the-trace-of-the-work-it-describes-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (change-vanilla! catalog 300)
      (let [span (recorder/span-named "catalog change-price")
            log  (first (filter #(str/starts-with? (:body %) "change-price")
                                (recorder/recorded-logs)))]
        (is (some? log) "the slice logged")
        (is (= (:trace-id span) (:trace-id log))
            "the log record names the trace it happened in")
        (is (= (:span-id span) (:span-id log))
            "and the span, so a log line and a span are two views of one event")))))

(deftest a-log-carries-typed-attributes-not-an-interpolated-sentence-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (change-vanilla! catalog 300)
      (let [log (first (filter #(str/starts-with? (:body %) "change-price")
                               (recorder/recorded-logs)))]
        (is (= 300 (get (:attributes log) "es.price_cents"))
            "the price arrives as a number, not as text a backend must parse")
        (is (= "accepted" (get (:attributes log) "es.outcome")))
        (is (not (str/includes? (:body log) "300"))
            "and is not buried in the message for a regex to dig out later")))))

;; ---------------------------------------------------------------------------
;; One trace across two modules
;; ---------------------------------------------------------------------------

(deftest one-trace-spans-both-modules-and-both-transactions-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (change-vanilla! catalog 300)
      (system/relay-catalog! app)
      (let [command (recorder/span-named "catalog change-price")
            publish (recorder/span-named "catalog publish-price-changed-to-ordering")
            receive (recorder/span-named "ordering catalog-price-changed")]
        (is (every? some? [command publish receive]))
        (testing "one trace, even though nothing shared a transaction"
          (is (= (:trace-id command) (:trace-id publish) (:trace-id receive))))
        (testing "and the causal chain is the real one"
          (is (= (:span-id command) (:parent-id publish))
              "the publish belongs to the price change that caused it")
          (is (= (:span-id publish) (:parent-id receive))
              "the receive belongs to the publish that delivered it"))
        (testing "each side is labelled as what it is"
          (is (= :PRODUCER (:kind publish)))
          (is (= :CONSUMER (:kind receive))))))))

(deftest trace-context-is-frozen-with-the-outbox-row-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (change-vanilla! catalog 300)
      (let [command (recorder/span-named "catalog change-price")
            row     (jdbc/execute-one!
                     (jdbc/get-datasource (:catalog (postgres/config)))
                     ["SELECT traceparent FROM catalog.outbox"])]
        (is (str/includes? (:outbox/traceparent row) (:trace-id command))
            "the trace was captured in the price-change transaction")
        (is (str/includes? (:outbox/traceparent row) (:span-id command))
            "pointing at the command's own span, not at whenever the relay ran")))))

(deftest a-redelivery-is-a-new-span-but-the-same-fact-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (let [{:keys [message headers]} (first (delivered-to
                                              (system/relay-catalog! app)
                                              :ordering))
            fact-id  (get-in message [:payload :fact-id])
            first-id (:span-id (recorder/span-named "ordering catalog-price-changed"))]
        (recorder/clear!)
        (ordering/receive! ordering {:headers headers
                                     :message (assoc message :message/id (random-uuid))})
        (let [again (recorder/span-named "ordering catalog-price-changed")]
          (is (not= first-id (:span-id again))
              "a second delivery is a second piece of work and gets its own span")
          (is (= (str fact-id) (get (:attributes again) "es.fact_id"))
              "carrying the same fact id, which is what makes it a duplicate")
          (is (= "duplicate" (get (:attributes again) "es.outcome"))))))))

(deftest the-inbox-persists-correlation-and-not-trace-context-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (change-vanilla! catalog 300)
      (system/relay-catalog! app)
      (let [columns (jdbc/execute!
                     (jdbc/get-datasource (:ordering (postgres/config)))
                     ["SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'ordering' AND table_name = 'inbox'"])
            names   (set (map :columns/column_name columns))]
        (is (contains? names "correlation_id")
            "correlation answers a business question years from now")
        (is (not (contains? names "traceparent"))
            "trace context is sampled and expires; it is not business data")))))

;; ---------------------------------------------------------------------------
;; A refusal is not an error
;; ---------------------------------------------------------------------------

(deftest a-business-refusal-is-a-successful-span-test
  (fixture/with-system
    (fn [{:keys [catalog ordering]}]
      (testing "malformed input — lab 23's 400"
        (catalog/change-price! catalog {:command-id (random-uuid)
                                        :correlation-id (random-uuid)
                                        :product-id vanilla
                                        :product-name "vanilla"
                                        :price-cents 0})
        (let [span (recorder/span-named "catalog change-price")]
          (is (not= :ERROR (:status span)))
          (is (= "malformed" (get (:attributes span) "es.outcome")))))

      (testing "a rule refusing while the answer is still open — lab 23's 422"
        (recorder/clear!)
        (ordering/place-order! ordering (place-order-request order-1 2))
        (let [span (recorder/span-named "ordering place-order")]
          (is (not= :ERROR (:status span)))
          (is (= "price-unavailable" (get (:attributes span) "es.outcome")))))

      (testing "nothing recorded an exception"
        (is (empty? (mapcat :events (recorder/recorded-spans))))))))

(deftest an-infrastructure-failure-is-an-error-span-test
  (let [one-message-id (random-uuid)
        ids            (atom [(random-uuid) one-message-id
                              (random-uuid) one-message-id])
        next-id        (fn [] (let [[id & remaining] @ids] (reset! ids remaining) id))]
    (fixture/with-system
      {:new-id next-id}
      (fn [{:keys [catalog]}]
        (change-vanilla! catalog 300)
        (recorder/clear!)
        ;; The second command reuses an outbox message id, so Postgres rejects
        ;; the insert and the whole command rolls back. That is the machine
        ;; failing, not the business refusing.
        (is (thrown? java.sql.SQLException (change-vanilla! catalog 450)))
        (let [span (recorder/span-named "catalog change-price")]
          (is (= :ERROR (:status span))
              "an unexpected failure is what a span's error status is for")
          (is (= ["exception"] (:events span))
              "with the exception recorded on it for whoever is paged"))))))

;; ---------------------------------------------------------------------------
;; Metrics
;; ---------------------------------------------------------------------------

(deftest the-counter-counts-machine-outcomes-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (change-vanilla! catalog 300)
      (change-vanilla! catalog 450)
      (let [counts (recorder/counter-values)
            accepted (get counts {"es.module"  "catalog"
                                  "es.request" "change-price"
                                  "es.outcome" "accepted"})]
        (is (some? accepted) "outcomes are counted, grouped by the same words")
        (is (<= 2 accepted)
            "a count, deliberately not the answer to \"how many prices changed\"")))))
