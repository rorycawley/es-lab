(ns lab29.ordering.fulfilment
  "The process manager: one order's conversation, remembered.

  The messaging document draws the line between a policy and a process manager
  at memory. A policy decides from the triggering fact plus ordinary domain
  information; a process manager needs to remember where a multi-step
  conversation had got to.

  This one qualifies, and the test for it is the one assertion below that a
  policy could not make: a `payment-succeeded` for an order that is not
  awaiting payment is **refused**. Only something holding the conversation's
  state can know that, and only something that knows it can tell a legitimate
  redelivery from a step arriving twice for two different reasons.

  What it does not contain is delivery state. Attempts, retries and dead
  letters belong to the transport; a process manager that counts redeliveries
  has started coordinating infrastructure instead of business."
  (:require [lab29.notifications.contract :as notifications-contract]
            [lab29.ordering.outbox :as outbox]
            [lab29.payments.contract :as payments-contract]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:headers [:map-of :string :string]]
   [:message payments-contract/PaymentSucceeded]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- process [db order-id]
  (jdbc/execute-one!
   db
   ["SELECT order_id, correlation_id, state, total_cents, customer_email
       FROM ordering.fulfilment WHERE order_id = ?"
    order-id]
   opts))

(defn handle!
  [{:keys [datasource new-id]} {:keys [message]}]
  (let [{:keys [fact-id order-id amount-cents customer-email]} (:payload message)
        {:keys [correlation-id]} (:metadata message)]
    (jdbc/with-transaction [tx datasource]
      (if-let [current (process tx order-id)]
        (case (:state current)
          "awaiting-payment"
          (do
            (jdbc/execute-one!
             tx
             ["UPDATE ordering.fulfilment SET state = 'paid', settled_at = now()
                WHERE order_id = ? AND state = 'awaiting-payment'"
              order-id])
            ;; The next step in the sequence this process owns. A policy could
            ;; have sent it too -- but then two things would own one workflow,
            ;; which is how coordination bugs are born.
            (outbox/enqueue! tx (notifications-contract/send-receipt
                                 (new-id) fact-id correlation-id
                                 (new-id) order-id amount-cents customer-email))
            {:accepted {:order-id order-id :state "paid"}})

          ;; Already paid. A redelivered fact must not advance a conversation
          ;; that has already moved on, and must not send a second receipt.
          {:duplicate {:order-id order-id :state (:state current)}})
        ;; A payment for an order this module has no process for. Not an
        ;; error: Payments may legitimately be charging for something else.
        {:unmatched order-id}))))
