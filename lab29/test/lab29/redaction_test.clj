(ns lab29.redaction-test
  "Telemetry is a data-protection surface.

  Lab 15 sealed a personal field at the application edge so that erasing one
  key erases one subject from an append-only history. That whole mechanism is
  defeated by a span attribute, because the copy in a telemetry backend is
  outside the store lab 15 controls, on somebody else's retention schedule, and
  no crypto-shredding reaches it.

  So the customer's email is here deliberately. Ordering has to keep it — a
  receipt goes somewhere — and this suite asserts it never leaves through the
  other pipe."
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
(def customer "ada@example.com")

(defn- everything-emitted
  "Every string this process handed the telemetry pipeline."
  []
  (let [spans (recorder/recorded-spans)
        logs  (recorder/recorded-logs)]
    (concat (map :name spans)
            (mapcat (comp keys :attributes) spans)
            (mapcat (comp vals :attributes) spans)
            (mapcat :events spans)
            (keep :body logs)
            (mapcat (comp keys :attributes) logs)
            (mapcat (comp vals :attributes) logs))))

(deftest personal-data-is-kept-by-the-module-and-never-exported-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (catalog/change-price! catalog {:command-id (random-uuid)
                                      :correlation-id (random-uuid)
                                      :product-id vanilla
                                      :product-name "vanilla"
                                      :price-cents 300})
      (system/relay-catalog! app)
      (let [request {:order-id order-1
                     :correlation-id (random-uuid)
                     :product-id vanilla
                     :quantity 2
                     :customer-email customer
                     :payment-method "pm_card_visa"}]
        (ordering/place-order! ordering request)

        (testing "Ordering did keep it — this is not a test that nothing happened"
          (is (= customer
                 (:orders/customer_email
                  (jdbc/execute-one!
                   (jdbc/get-datasource (:ordering (postgres/config)))
                   ["SELECT customer_email FROM ordering.orders WHERE order_id = ?"
                    order-1])))))

        (testing "the query response does not carry it either"
          (is (not (contains? (:found (ordering/get-order ordering {:order-id order-1}))
                              :customer-email))))

        (testing "and no span or log record does"
          (let [emitted (everything-emitted)]
            (is (seq emitted) "telemetry was in fact produced")
            (is (empty? (filter #(str/includes? (str %) customer) emitted)))
            (is (empty? (filter #(str/includes? (str %) "customer") emitted))
                "not even the field name, which would tell a reader to go looking")))))))

(deftest a-search-query-is-not-exported-test
  ;; The search box is the one input a user fills with whatever they like,
  ;; including their own address while looking for their own order. Lab 26's
  ;; allow-list is only an allow-list if free text cannot walk through it.
  (fixture/with-system
    (fn [{:keys [ordering]}]
      (ordering/search ordering {:query customer})
      (let [span (recorder/span-named "ordering search-orders")]
        (is (= "no-matches" (get (:attributes span) "es.outcome"))
            "the search really happened")
        (is (= (count customer) (get (:attributes span) "es.query_length"))
            "its shape is exported, as a number")
        (is (empty? (filter #(str/includes? (str %) customer) (everything-emitted)))
            "its content is not")))))

(deftest a-refusal-does-not-leak-what-a-success-would-not-test
  (fixture/with-system
    (fn [{:keys [ordering]}]
      ;; The failure path is where redaction usually breaks: somebody adds the
      ;; whole request to the error so it can be debugged.
      (ordering/place-order! ordering {:order-id order-1
                                       :correlation-id (random-uuid)
                                       :product-id vanilla
                                       :quantity 2
                                       :customer-email customer
                                       :payment-method "pm_card_visa"})
      (is (= "price-unavailable"
             (get (:attributes (recorder/span-named "ordering place-order"))
                  "es.outcome"))
          "the request really was refused")
      (is (empty? (filter #(str/includes? (str %) customer) (everything-emitted)))))))

(deftest a-malformed-request-does-not-echo-itself-into-telemetry-test
  (fixture/with-system
    (fn [{:keys [ordering]}]
      (ordering/place-order! ordering {:order-id order-1
                                       :correlation-id (random-uuid)
                                       :product-id vanilla
                                       :quantity 0
                                       :customer-email customer
                                       :payment-method "pm_card_visa"})
      (is (= "malformed"
             (get (:attributes (recorder/span-named "ordering place-order"))
                  "es.outcome")))
      (is (empty? (filter #(str/includes? (str %) customer) (everything-emitted)))
          "a rejection explains which field failed, not what the others held"))))
