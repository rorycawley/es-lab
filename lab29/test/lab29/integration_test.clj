(ns lab29.integration-test
  "Four modules, three contracts, two providers, no shared transaction."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.fixture :as fixture]
            [lab29.notifications.adapter.memory :as memory-emailer]
            [lab29.notifications.api :as notifications]
            [lab29.ordering.api :as ordering]
            [lab29.payments.api :as payments]
            [lab29.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- delivered-to [summary consumer]
  (filterv #(= consumer (:consumer %)) (:delivered summary)))

(defn- stock! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- order!
  ([ordering order-id quantity] (order! ordering order-id quantity "pm_card_visa"))
  ([ordering order-id quantity payment-method]
   (ordering/place-order! ordering {:order-id order-id
                                    :correlation-id (random-uuid)
                                    :product-id vanilla
                                    :quantity quantity
                                    :customer-email "ada@example.test"
                                    ;; An opaque token from checkout. Its value
                                    ;; happens to be a provider's test card,
                                    ;; which is test data rather than code.
                                    :payment-method payment-method})))

;; ---------------------------------------------------------------------------

(deftest an-order-travels-through-four-modules-test
  (fixture/with-system
    (fn [{:keys [catalog ordering payments] :as app}]
      (stock! catalog 300)
      (system/relay-catalog! app)
      (let [order-id (random-uuid)]
        (is (:accepted (order! ordering order-id 2)))

        (testing "nothing has happened downstream until the relay runs"
          (is (= order-id (:not-found (payments/get-payment payments {:order-id order-id})))))

        (testing "ordering announces, payments charges"
          (is (= 1 (:ordering (system/relay-all! app))))
          (let [payment (:found (payments/get-payment payments {:order-id order-id}))]
            (is (= "authorized" (:status payment)))
            (is (= 600 (:amount-cents payment)))
            (is (string? (:gateway-reference payment)))))

        (testing "payments announces, notifications emails"
          (system/relay-payments! app)
          (system/relay-ordering! app)
          (let [sent (memory-emailer/sent-messages (:emailer app))]
            (is (= 1 (count sent)))
            (is (= "ada@example.test" (:to (first sent))))
            (is (re-find #"€6\.00" (:body (first sent))))))))))

(deftest a-declined-card-stops-the-conversation-test
  (fixture/with-system
    (fn [{:keys [catalog ordering payments] :as app}]
      (stock! catalog 300)
      (system/relay-catalog! app)
      (let [order-id (random-uuid)]
        (order! ordering order-id 1 "pm_card_chargeDeclined")
        (system/relay-ordering! app)

        (let [payment (:found (payments/get-payment payments {:order-id order-id}))]
          (is (= "declined" (:status payment)))
          (is (= "card_declined" (:decline-reason payment))))

        (testing "and no receipt is promised for money that never moved"
          (is (empty? (:delivered (system/relay-payments! app))))
          (is (empty? (memory-emailer/sent-messages (:emailer app)))))))))

(deftest a-refused-recipient-does-not-undo-the-payment-test
  (fixture/with-system
    (fn [{:keys [catalog ordering payments notifications] :as app}]
      (stock! catalog 300)
      (system/relay-catalog! app)
      (let [order-id (random-uuid)]
        (ordering/place-order! ordering {:order-id order-id
                                         :correlation-id (random-uuid)
                                         :product-id vanilla
                                         :quantity 1
                                         :customer-email "bounced@example.test"
                                         :payment-method "pm_card_visa"})
        (system/relay-ordering! app)
        (system/relay-payments! app)
        (let [receipt (first (delivered-to (system/relay-ordering! app)
                                           :notifications))
              fact-id (get-in receipt [:message :data :receipt-id])]
          (testing "the money moved and stays moved"
            (is (= "authorized" (:status (:found (payments/get-payment
                                                  payments {:order-id order-id}))))))
          (testing "the receipt failed and says why"
            (let [row (:found (notifications/get-notification
                               notifications {:fact-id fact-id}))]
              (is (= "failed" (:status row)))
              (is (= "invalid_recipient" (:failure-reason row)))
              (is (= 1 (:attempts row)))))
          (testing "an undeliverable receipt is not a reason to refund"
            ;; Two integrations, two failure domains. Collapsing them is how a
            ;; bounced email starts reversing charges.
            (is (= "authorized" (:status (:found (payments/get-payment
                                                  payments {:order-id order-id})))))))))))

(deftest modules-know-only-the-contracts-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (stock! catalog 300)
      (system/relay-catalog! app)
      (order! ordering (random-uuid) 1)
      (let [[{:keys [message]}] (delivered-to (system/relay-ordering! app) :payments)]
        (is (= :payments/charge-order (:command/type message)))
        (is (= #{:order-id :order-fact-id :total-cents
                 :customer-email :payment-method}
               (set (keys (:data message)))))
        (is (= #{:causation-id :correlation-id} (set (keys (:metadata message))))))
      (let [[{:keys [message]}] (delivered-to (system/relay-payments! app) :ordering)]
        (is (= :payments/payment-succeeded (:event/type message)))
        (is (not (contains? (:payload message) :gateway-reference))
            "which provider took the money is Payments' business alone")))))

(deftest the-provider-is-a-deployment-choice-test
  ;; Same modules, same assertions, different providers underneath.
  (fixture/with-system
    (fn [{:keys [payments notifications]}]
      (is (= "memory" (payments/provider payments)))
      (is (= "memory" (notifications/provider notifications)))))
  (fixture/with-providers
    (fn [{:keys [app]}]
      (is (= "stripe" (payments/provider (:payments app))))
      (is (= "sendgrid" (notifications/provider (:notifications app)))))))

(deftest the-whole-thing-works-against-real-providers-test
  (fixture/with-providers
    (fn [{:keys [app stripe sendgrid]}]
      (let [{:keys [catalog ordering payments]} app
            order-id (random-uuid)]
        (stock! catalog 300)
        (system/relay-catalog! app)
        (order! ordering order-id 2)
        (system/relay-all! app)
        (system/relay-ordering! app)

        (is (= "authorized" (:status (:found (payments/get-payment
                                              payments {:order-id order-id})))))
        (is (= 1 (count (fake-stripe/charges stripe))))
        (is (= 1 (count (fake-sendgrid/sent sendgrid))))
        (is (re-find #"^pi_" (:gateway-reference
                              (:found (payments/get-payment payments {:order-id order-id})))))))))
