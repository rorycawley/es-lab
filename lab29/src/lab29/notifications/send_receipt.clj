(ns lab29.notifications.send-receipt
  "The complete `Send receipt` slice: consume a settled payment, email a
  receipt.

  New in lab 29: a **command handler**. Lab 28 sent a receipt by subscribing to
  the fact that a payment had succeeded, which quietly assumed exactly one
  module would want to. The order's process manager now asks for the receipt,
  by name, once.

  Structurally this is `charge_order.clj` again -- claim, call out, record --
  and the repetition is the point. What differs is the guarantee, and it
  differs because the provider is different, not because the code is worse:

      Payments      idempotency key   two calls, one charge
      Notifications no such thing     two calls, two emails

  So the ledger here does a smaller job. It cannot prevent a duplicate receipt;
  it can only make one visible, count the attempts, and stop the *next* poll
  from sending a third. Lab 20 said an exactly-once local effect is not
  exactly-once delivery and named email as the case that proves it. This is
  that case, built."
  (:require [lab29.notifications.contract :as contract]
            [lab29.notifications.port :as port]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:headers [:map-of :string :string]]
   [:message contract/SendReceipt]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- money [cents] (format "€%.2f" (/ cents 100.0)))

(defn receipt
  "The pure part: what the customer is told. Values in, values out."
  [{:keys [order-id amount-cents]}]
  {:subject (str "Your receipt for order " order-id)
   :body    (str "Thank you. We charged " (money amount-cents)
                 " for order " order-id ".")})

(defn- notification-for-fact [db fact-id]
  (jdbc/execute-one!
   db
   ["SELECT notification_id, fact_id, recipient, subject, status,
            provider_reference, attempts, failure_reason
       FROM notifications.notification WHERE fact_id = ?"
    fact-id]
   opts))

(defn- claim!
  [datasource {:keys [fact-id message-id correlation-id notification-id
                      recipient subject body]}]
  (jdbc/with-transaction [tx datasource]
    (when (jdbc/execute-one!
           tx
           ["INSERT INTO notifications.inbox (fact_id, first_message_id, correlation_id)
             VALUES (?, ?, ?)
             ON CONFLICT (fact_id) DO NOTHING
             RETURNING fact_id"
            fact-id message-id correlation-id])
      (jdbc/execute-one!
       tx
       ["INSERT INTO notifications.notification
           (notification_id, fact_id, recipient, subject, body, status, correlation_id)
         VALUES (?, ?, ?, ?, ?, 'queued', ?)
         ON CONFLICT (fact_id) DO NOTHING
         RETURNING notification_id"
        notification-id fact-id recipient subject body correlation-id]))))

(defn- record!
  [datasource notification-id {:keys [outcome reference because]}]
  (jdbc/execute-one!
   datasource
   ["UPDATE notifications.notification
        SET status = ?, provider_reference = ?, failure_reason = ?,
            attempts = attempts + 1,
            sent_at = CASE WHEN ? = 'sent' THEN now() ELSE sent_at END
      WHERE notification_id = ?"
    (if (= :sent outcome) "sent" "failed") reference because
    (if (= :sent outcome) "sent" "failed") notification-id]))

(defn handle!
  [{:keys [datasource emailer new-id]} {:keys [message]}]
  (let [{:keys [correlation-id]} (:metadata message)
        {:keys [receipt-id order-id amount-cents customer-email]} (:data message)
        fact-id receipt-id
        existing (notification-for-fact datasource fact-id)]
    (if (and existing (not= "queued" (:status existing)))
      ;; Already attempted and answered. A redelivery of the same fact must not
      ;; produce a second receipt -- which is the *one* duplicate this module
      ;; can actually prevent, because it is on our side of the wire.
      {:duplicate (dissoc existing :recipient)}
      (let [{:keys [subject body]} (receipt {:order-id order-id :amount-cents amount-cents})
            notification-id        (or (:notification-id existing) (new-id))]
        (when-not existing
          (claim! datasource {:fact-id fact-id
                              :message-id (:message/id message)
                              :correlation-id correlation-id
                              :notification-id notification-id
                              :recipient customer-email
                              :subject subject
                              :body body}))
        (let [answer (port/send! emailer {:notification-id notification-id
                                          :to customer-email
                                          :subject subject
                                          :body body})]
          (record! datasource notification-id answer)
          (let [row (dissoc (notification-for-fact datasource fact-id) :recipient)]
            (if (= :sent (:outcome answer))
              {:accepted row}
              {:rejected :delivery-refused :because (:because answer) :notification row})))))))
