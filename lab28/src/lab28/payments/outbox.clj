(ns lab28.payments.outbox
  "Payments' module-owned outbox. Same shape as Catalog's in lab 25."
  (:require [lab28.payments.contract :as contract]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn pending
  [{:keys [datasource]}]
  (mapv (fn [{:keys [message-id fact-id causation-id correlation-id traceparent
                     payment-id order-id amount-cents customer-email attempts]}]
          {:attempts attempts
           :headers (if traceparent {"traceparent" traceparent} {})
           :message (contract/payment-succeeded message-id fact-id causation-id
                                                correlation-id payment-id order-id
                                                amount-cents customer-email)})
        (jdbc/execute!
         datasource
         ["SELECT message_id, fact_id, causation_id, correlation_id, traceparent, attempts,
                  payment_id, order_id, amount_cents, customer_email
             FROM payments.outbox
            WHERE published = FALSE AND dead = FALSE
            ORDER BY created_order"]
         {:builder-fn rs/as-unqualified-kebab-maps})))

(defn mark-published!
  [{:keys [datasource]} message-id]
  (jdbc/execute-one!
   datasource
   ["UPDATE payments.outbox SET published = TRUE WHERE message_id = ?" message-id]))

(defn record-failure!
  "This delivery did not go. Remember how many times, and why."
  [{:keys [datasource]} message-id detail]
  (jdbc/execute-one!
   datasource
   ["UPDATE payments.outbox
        SET attempts = attempts + 1, last_error = ?
      WHERE message_id = ?"
    detail message-id]))

(defn dead-letter!
  "Move a message out of the queue and into the graveyard, in one transaction.

  The whole message goes with it, because a dead letter you cannot replay is a
  dead letter you may as well have dropped -- and it goes as EDN rather than
  JSON on purpose. `json/write-str` names a key with `name`, so `:message/type`
  is written as `\"type\"` and the namespace is gone. The body would look fine
  and be unreplayable. Lab 19 made this point about JSONB and keywords; it is
  the same loss, in a new place, and this body is ours rather than a wire
  format, so nothing is given up by keeping it exact."
  [{:keys [datasource]} {:keys [message]} attempts detail]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute-one!
     tx
     ["INSERT INTO payments.dead_letter
         (message_id, message_type, fact_id, correlation_id, message_body,
          attempts, last_error)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (message_id) DO NOTHING"
      (:message/id message)
      (str (symbol (:message/type message)))
      (get-in message [:payload :fact-id])
      (get-in message [:metadata :correlation-id])
      (pr-str message)
      attempts detail])
    (jdbc/execute-one!
     tx
     ["UPDATE payments.outbox SET dead = TRUE, attempts = ?, last_error = ?
        WHERE message_id = ?"
      attempts detail (:message/id message)])))

(defn dead-letters
  "The graveyard, for an operator."
  [{:keys [datasource]}]
  (jdbc/execute!
   datasource
   ["SELECT message_id, message_type, fact_id, attempts, last_error, died_at
       FROM payments.dead_letter ORDER BY died_at"]
   {:builder-fn rs/as-unqualified-kebab-maps}))

(defn revive!
  "Put a dead letter back on the queue, once somebody has fixed the reason.

  The attempt counter resets, because the count was evidence about a
  situation that has since changed. This is deliberately a manual act: a dead
  letter queue that drains itself is just a slower retry loop."
  [{:keys [datasource]} message-id]
  (jdbc/with-transaction [tx datasource]
    (when-let [row (jdbc/execute-one!
                    tx
                    ["SELECT message_body FROM payments.dead_letter
                       WHERE message_id = ?" message-id]
                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (jdbc/execute-one!
       tx
       ["UPDATE payments.outbox
            SET dead = FALSE, attempts = 0, last_error = NULL
          WHERE message_id = ?" message-id])
      (jdbc/execute-one!
       tx
       ["DELETE FROM payments.dead_letter WHERE message_id = ?" message-id])
      {:revived message-id :message-body (:message-body row)})))
