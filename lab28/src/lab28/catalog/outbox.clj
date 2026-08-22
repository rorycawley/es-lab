(ns lab28.catalog.outbox
  "Catalog's module-owned outbox.

  Updating today's price and recording its integration message share Catalog's
  transaction. Delivery crosses into another database and therefore cannot.

  Each pending row becomes a **delivery**: the public message, and the
  transport headers carrying the trace context frozen with it."
  (:require [lab28.catalog.contract :as contract]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn pending
  [{:keys [datasource]}]
  (mapv (fn [{:keys [message-id fact-id causation-id correlation-id traceparent
                     product-id product-name price-cents attempts]}]
          {:attempts attempts
           :headers (if traceparent {"traceparent" traceparent} {})
           :message (contract/price-changed message-id fact-id causation-id
                                            correlation-id product-id
                                            product-name price-cents)})
        (jdbc/execute!
         datasource
         ["SELECT message_id, fact_id, causation_id, correlation_id, traceparent, attempts,
                  product_id, product_name, price_cents
             FROM catalog.outbox
            WHERE published = FALSE AND dead = FALSE
            ORDER BY created_order"]
         {:builder-fn rs/as-unqualified-kebab-maps})))

(defn mark-published!
  [{:keys [datasource]} message-id]
  (jdbc/execute-one!
   datasource
   ["UPDATE catalog.outbox SET published = TRUE WHERE message_id = ?"
    message-id]))

(defn record-failure!
  [{:keys [datasource]} message-id detail]
  (jdbc/execute-one!
   datasource
   ["UPDATE catalog.outbox
        SET attempts = attempts + 1, last_error = ?
      WHERE message_id = ?"
    detail message-id]))

(defn dead-letter!
  [{:keys [datasource]} {:keys [message]} attempts detail]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute-one!
     tx
     ["INSERT INTO catalog.dead_letter
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
     ["UPDATE catalog.outbox SET dead = TRUE, attempts = ?, last_error = ?
        WHERE message_id = ?"
      attempts detail (:message/id message)])))

(defn dead-letters
  [{:keys [datasource]}]
  (jdbc/execute!
   datasource
   ["SELECT message_id, message_type, fact_id, attempts, last_error, died_at
       FROM catalog.dead_letter ORDER BY died_at"]
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
                    ["SELECT message_body FROM catalog.dead_letter
                       WHERE message_id = ?" message-id]
                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (jdbc/execute-one!
       tx
       ["UPDATE catalog.outbox
            SET dead = FALSE, attempts = 0, last_error = NULL
          WHERE message_id = ?" message-id])
      (jdbc/execute-one!
       tx
       ["DELETE FROM catalog.dead_letter WHERE message_id = ?" message-id])
      {:revived message-id :message-body (:message-body row)})))
