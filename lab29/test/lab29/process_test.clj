(ns lab29.process-test
  "A process manager and a policy, and the one test that tells them apart.

  Both react to facts. The difference is memory, and the messaging document's
  rule is a question you can answer without reading any code:

  > Can the reaction be decided from the triggering fact plus ordinary domain
  > information, without remembering a multi-step conversation?

  `ordering.fulfilment` cannot -- \"has this order been paid for?\" is not in
  the message. `websub.adapter` can -- \"this product's public representation
  changed\" is entirely in the message. So one has a table and one does not,
  and the assertions below are the observable consequence of that."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.catalog.contract :as catalog-contract]
            [lab29.fixture :as fixture]
            [lab29.notifications.api :as notifications]
            [lab29.ordering.api :as ordering]
            [lab29.payments.api :as payments]
            [lab29.payments.contract :as payments-contract]
            [lab29.postgres :as postgres]
            [lab29.system :as system]
            [lab29.websub.adapter :as websub-adapter]
            [lab29.websub.topics :as topics]
            [next.jdbc :as jdbc]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- ready! [{:keys [catalog] :as app}]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents 300})
  (system/relay-catalog! app))

(defn- order! [{:keys [ordering]} order-id]
  (ordering/place-order! ordering {:order-id order-id
                                   :correlation-id (random-uuid)
                                   :product-id vanilla
                                   :quantity 2
                                   :customer-email "ada@example.test"
                                   :payment-method "pm_card_visa"}))

(defn- state-of [order-id]
  (:fulfilment/state
   (jdbc/execute-one! (jdbc/get-datasource (:ordering (postgres/config)))
                      ["SELECT state FROM ordering.fulfilment WHERE order_id = ?"
                       order-id])))

;; ---------------------------------------------------------------------------
;; The alternation the document describes
;; ---------------------------------------------------------------------------

(deftest a-request-a-fact-a-request-a-fact-test
  (fixture/with-system
    (fn [{:keys [payments notifications] :as app}]
      (ready! app)
      (let [order-id (random-uuid)]
        (order! app order-id)
        (is (= "awaiting-payment" (state-of order-id))
            "the process starts in the same transaction as the order")

        (testing "the request reaches Payments and comes back as a fact"
          (system/relay-ordering! app)
          (is (= "authorized" (:status (:found (payments/get-payment
                                                payments {:order-id order-id})))))
          (is (= "awaiting-payment" (state-of order-id))
              "and the process has not moved until the fact is delivered"))

        (testing "the fact advances the process, which asks for the receipt"
          (system/relay-payments! app)
          (is (= "paid" (state-of order-id)))
          (system/relay-ordering! app)
          (is (= 1 (count (notifications/audit-log notifications)))))))))

;; ---------------------------------------------------------------------------
;; What only a process manager can do
;; ---------------------------------------------------------------------------

(deftest a-process-manager-refuses-a-step-that-has-already-happened-test
  ;; This is the assertion a policy could not make. The message is identical
  ;; both times; only something holding the conversation's state can tell the
  ;; second delivery from the first.
  (fixture/with-system
    (fn [{:keys [ordering] :as app}]
      (ready! app)
      (let [order-id (random-uuid)]
        (order! app order-id)
        (system/relay-ordering! app)
        (let [[settled] (:delivered (system/relay-payments! app))
              delivery  (select-keys settled [:headers :message])]
          (is (= "paid" (state-of order-id)))
          (is (= {:order-id order-id :state "paid"}
                 (:duplicate (ordering/receive! ordering delivery)))
              "a redelivered fact does not advance a conversation that moved on")
          (is (= "paid" (state-of order-id))))))))

(deftest a-process-manager-ignores-a-fact-about-something-it-is-not-running-test
  (fixture/with-system
    (fn [{:keys [ordering]}]
      (let [stranger (random-uuid)
            message  (payments-contract/payment-succeeded
                      (random-uuid) (random-uuid) (random-uuid) (random-uuid)
                      (random-uuid) stranger 600 "ada@example.test")]
        (is (= stranger (:unmatched (ordering/receive!
                                     ordering {:headers {} :message message})))
            "not an error: Payments may legitimately charge for something else")))))

;; ---------------------------------------------------------------------------
;; What a policy does instead
;; ---------------------------------------------------------------------------

(deftest a-policy-does-not-care-what-order-facts-arrive-in-test
  ;; `websub.adapter` folds facts into a projection. Hand it the two facts in
  ;; either order and it converges on the same public resource, because there
  ;; is no conversation to be at the wrong point of.
  ;;
  ;; Driven with values rather than through Catalog, so the ordering under
  ;; test is the *delivery* order and not an accident of which command can
  ;; legally run first.
  (let [priced   #(catalog-contract/price-changed
                   (random-uuid) (random-uuid) (random-uuid) (random-uuid)
                   % "vanilla" 300)
        described #(catalog-contract/product-described
                    (random-uuid) (random-uuid) (random-uuid) (random-uuid)
                    % "a creamy vanilla flavour")]
    (doseq [[label build] [["priced, then described" (fn [id] [(priced id) (described id)])]
                           ["described, then priced" (fn [id] [(described id) (priced id)])]]]
      (fixture/with-system
        (fn [{:keys [websub]}]
          (let [product-id (random-uuid)]
            (doseq [message (build product-id)]
              (websub-adapter/handle! websub {:headers {} :message message}))
            (testing label
              (let [now (topics/representation (:datasource websub) product-id)]
                (is (= "vanilla" (:name now)))
                (is (= "a creamy vanilla flavour" (:description now)))
                (is (= 300 (:price-cents now)))))))))))

(deftest only-the-process-manager-keeps-state-test
  ;; A structural version of the same distinction, in case the behavioural
  ;; ones ever pass for the wrong reason.
  (is (re-find #"ordering\.fulfilment"
               (slurp "src/lab29/ordering/fulfilment.clj"))
      "the process manager reads and writes a table")
  (is (not (re-find #"CREATE TABLE websub\.process|websub\.fulfilment"
                    (slurp "resources/schema.sql")))
      "the policy has none to read"))
