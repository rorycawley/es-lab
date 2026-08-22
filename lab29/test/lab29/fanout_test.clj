(ns lab29.fanout-test
  "The defect lab 28 had, and the reason it never fired.

  Lab 28 marked a *message* delivered. Every event in it happened to have
  exactly one subscriber, so a shared delivery record and a per-consumer one
  behaved identically and nothing distinguished them. Adding a second consumer
  is what makes the difference visible, and it is the difference between
  fan-out and a shared fate."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.fixture :as fixture]
            [lab29.ordering.api :as ordering]
            [lab29.system :as system]
            [lab29.websub.topics :as topics]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- stock! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- broken [_]
  (throw (ex-info "this consumer is down" {:reason :consumer-unavailable})))

;; ---------------------------------------------------------------------------

(deftest one-fact-two-consumers-two-delivery-records-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)
      (let [summary (system/relay-catalog! app)]
        (is (= #{:ordering :websub} (set (map :consumer (:delivered summary))))
            "the same fact, delivered twice, to two modules that want it for
             different reasons")))))

(deftest a-broken-consumer-does-not-hold-up-the-other-test
  ;; The assertion lab 28 would fail. Its relay marked the message, so
  ;; WebSub being down meant Ordering was sent the price again on every retry
  ;; and could eventually see it dead-lettered.
  (fixture/with-system {:handlers {:websub broken}}
    (fn [{:keys [catalog ordering] :as app}]
      (stock! catalog 300)
      (let [summary (system/relay-catalog! app)]
        (testing "one consumer succeeded and one did not, on the same pass"
          (is (= [:ordering] (map :consumer (:delivered summary))))
          (is (= [:websub] (map :consumer (:failed summary)))))

        (testing "and the one that succeeded actually did the work"
          (is (= 300 (get-in (ordering/place-order!
                              ordering
                              {:order-id (random-uuid)
                               :correlation-id (random-uuid)
                               :product-id vanilla
                               :quantity 1
                               :customer-email "ada@example.test"
                               :payment-method "pm_card_visa"})
                             [:accepted :unit-price-cents])))))

      (testing "retrying retries only the consumer that failed"
        (let [again (system/relay-catalog! app)]
          (is (empty? (:delivered again))
              "Ordering is not sent the same price a second time")
          (is (= [:websub] (map :consumer (:failed again)))))))))

(deftest a-consumer-can-be-given-up-on-alone-test
  (fixture/with-system {:handlers {:websub broken}}
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)
      (dotimes [_ 3] (system/relay-catalog! app))
      (let [letters (catalog/dead-letters catalog)]
        (is (= [:websub] (map :consumer letters))
            "only the consumer that refused is in the graveyard")
        (is (= 1 (count letters))))
      (testing "and the message is settled, because everyone is finished with it"
        (is (= {:delivered [] :failed [] :dead-lettered []}
               (system/relay-catalog! app)))))))

(deftest a-consumer-deployed-late-still-receives-what-was-queued-test
  ;; Delivery records are expanded from the routing table when the relay first
  ;; sees a message, not written with it. A consumer that was not there when
  ;; the message was produced still gets one.
  (fixture/with-system {:handlers {:websub broken}}
    (fn [{:keys [catalog websub] :as app}]
      (stock! catalog 300)
      (dotimes [_ 3] (system/relay-catalog! app))
      (is (nil? (topics/representation (:datasource websub) vanilla))
          "WebSub was down for the whole of that")

      (testing "reviving its delivery hands it the message it missed"
        (let [[dead] (catalog/dead-letters catalog)]
          (catalog/revive! catalog (:message-id dead) (:consumer dead))))
      ;; The handler is broken in this system, so prove the record came back
      ;; rather than that a broken consumer suddenly worked.
      (is (= [:websub] (map :consumer (:failed (system/relay-catalog! app))))))))
