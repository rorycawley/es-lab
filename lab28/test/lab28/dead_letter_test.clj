(ns lab28.dead-letter-test
  "What happens when retrying cannot help.

  Retry and circuit breaking both assume the problem is the moment. A poison
  message -- one the consumer will refuse today, tomorrow and next week -- is
  a different failure, and treating it as a transient one produces the two
  quiet disasters a relay is prone to: a queue that never moves, or a loop
  that never stops."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [lab28.catalog.api :as catalog]
            [lab28.fixture :as fixture]
            [lab28.ordering.api :as ordering]
            [lab28.payments.api :as payments]
            [lab28.platform.bus :as bus]
            [lab28.platform.relay :as relay]
            [lab28.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- stock! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- deliver-price! [{:keys [ordering] :as app}]
  (doseq [delivery (:published (system/relay-catalog! app))]
    (ordering/receive! ordering (select-keys delivery [:headers :message]))))

(defn- order! [ordering order-id]
  (ordering/place-order! ordering {:order-id order-id
                                   :correlation-id (random-uuid)
                                   :product-id vanilla
                                   :quantity 1
                                   :customer-email "ada@example.test"
                                   :payment-method "pm_card_visa"}))

(defn- refusing-consumer!
  "Subscribe a consumer that will never accept anything."
  [app message-type reason]
  (bus/subscribe! (:bus app) message-type
                  (fn [_] (throw (ex-info "I will never accept this"
                                          {:reason reason})))))

;; ---------------------------------------------------------------------------

(deftest a-message-nobody-will-accept-eventually-stops-being-tried-test
  (fixture/with-system {:subscribe? false}
    (fn [{:keys [catalog ordering] :as app}]
      (stock! catalog 300)
      (deliver-price! app)
      (order! ordering (random-uuid))
      (refusing-consumer! app :ordering/order-placed :consumer-broken)

      (testing "the first passes record the failure and leave it queued"
        (dotimes [pass (dec relay/attempts-before-death)]
          (let [summary (system/relay-ordering! app)]
            (is (empty? (:published summary)))
            (is (= 1 (count (:failed summary))) (str "pass " pass))
            (is (empty? (:dead-lettered summary)))
            (is (empty? (ordering/dead-letters ordering))))))

      (testing "and then it is moved out of the way"
        (let [summary (system/relay-ordering! app)]
          (is (= 1 (count (:dead-lettered summary))))
          (is (= relay/attempts-before-death (:attempts (first (:dead-lettered summary)))))))

      (testing "the queue is empty, so nothing behind it is stuck"
        (is (= {:published [] :failed [] :dead-lettered []}
               (system/relay-ordering! app))))

      (testing "and the graveyard says what happened"
        (let [[dead] (ordering/dead-letters ordering)]
          (is (= "ordering/order-placed" (:message-type dead)))
          (is (= relay/attempts-before-death (:attempts dead)))
          (is (re-find #"consumer-broken" (:last-error dead)))
          (is (some? (:died-at dead))))))))

(deftest one-poison-message-does-not-block-the-ones-behind-it-test
  ;; The failure this exists to prevent. A relay that stops at the first
  ;; refusal turns one bad message into a total outage of the queue.
  (fixture/with-system {:subscribe? false}
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)
      (stock! catalog 450)
      (stock! catalog 500)
      (let [accepted (atom [])
            calls    (atom 0)]
        (bus/subscribe! (:bus app) :catalog/price-changed
                        (fn [{:keys [message]}]
                          (if (= 1 (swap! calls inc))
                            (throw (ex-info "not this one" {:reason :poison}))
                            (swap! accepted conj (get-in message [:payload :price-cents])))))
        (let [summary (system/relay-catalog! app)]
          (is (= 2 (count (:published summary))))
          (is (= 1 (count (:failed summary))))
          (is (= [450 500] @accepted)
              "the two behind the poison message went out on the same pass"))))))

(deftest a-dead-letter-can-be-revived-test
  ;; A graveyard you cannot drain is a bin. Fixing the consumer and putting
  ;; the message back is the whole point of keeping the body.
  (fixture/with-system {:subscribe? false}
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)
      (let [broken? (atom true)
            seen    (atom [])]
        (bus/subscribe! (:bus app) :catalog/price-changed
                        (fn [{:keys [message]}]
                          (if @broken?
                            (throw (ex-info "still broken" {:reason :consumer-broken}))
                            (swap! seen conj (get-in message [:payload :price-cents])))))
        (dotimes [_ relay/attempts-before-death] (system/relay-catalog! app))
        (let [[dead] (catalog/dead-letters catalog)]
          (is (some? dead))

          (testing "the body kept enough to send it again"
            (let [{:keys [revived message-body]} (catalog/revive! catalog (:message-id dead))
                  message (edn/read-string message-body)]
              (is (= (:message-id dead) revived))
              (is (= :catalog/price-changed (:message/type message))
                  "the namespace survived, which JSON would have dropped")
              (is (uuid? (:message/id message)))
              (is (= 300 (get-in message [:payload :price-cents])))))

          (testing "and once the consumer is fixed it goes"
            (reset! broken? false)
            (is (= 1 (count (:published (system/relay-catalog! app)))))
            (is (= [300] @seen))
            (is (empty? (catalog/dead-letters catalog))
                "the graveyard is empty again")))))))

(deftest a-healthy-queue-has-an-empty-graveyard-test
  ;; The assertion that makes \"anything in a dead letter table\" a page-worthy
  ;; alert rather than background noise.
  (fixture/with-system
    (fn [{:keys [catalog ordering payments] :as app}]
      (stock! catalog 300)
      (order! ordering (random-uuid))
      (system/relay-all! app)
      (order! ordering (random-uuid))
      (system/relay-all! app)
      (doseq [[label letters] [["catalog" (catalog/dead-letters catalog)]
                               ["ordering" (ordering/dead-letters ordering)]
                               ["payments" (payments/dead-letters payments)]]]
        (is (empty? letters) (str label " gave up on something"))))))
