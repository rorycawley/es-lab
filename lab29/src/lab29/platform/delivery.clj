(ns lab29.platform.delivery
  "One durable record per consumer, per message.

  This is the difference between fan-out and a shared fate. Labs 25 to 28
  marked the *message* delivered, so two consumers had one row between them:
  either refusing it meant the other was sent it again on every retry, and a
  message most consumers had accepted could still be dead-lettered by one that
  had not.

  Splitting the record splits the failure domain, which is the only thing that
  makes \"zero to many consumers\" mean anything operationally."
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn expand!
  "Make sure this message has a delivery record for each consumer.

  Idempotent, and done at relay time rather than at write time so that a
  consumer deployed after a message was queued still receives it. The outbox
  row is the durable thing; these are derived from it and the routing table."
  [datasource schema message-id consumers]
  (doseq [consumer consumers]
    (jdbc/execute-one!
     datasource
     [(str "INSERT INTO " schema ".delivery (message_id, consumer)
            VALUES (?, ?) ON CONFLICT (message_id, consumer) DO NOTHING")
      message-id (name consumer)])))

(defn pending
  "Consumers still waiting for this message."
  [datasource schema message-id]
  (mapv (fn [row] (update row :consumer keyword))
        (jdbc/execute!
         datasource
         [(str "SELECT consumer, attempts FROM " schema ".delivery
                 WHERE message_id = ? AND delivered = FALSE AND dead = FALSE
                 ORDER BY consumer")
          message-id]
         opts)))

(defn delivered!
  [datasource schema message-id consumer]
  (jdbc/execute-one!
   datasource
   [(str "UPDATE " schema ".delivery SET delivered = TRUE
           WHERE message_id = ? AND consumer = ?")
    message-id (name consumer)]))

(defn failed!
  [datasource schema message-id consumer detail]
  (jdbc/execute-one!
   datasource
   [(str "UPDATE " schema ".delivery
             SET attempts = attempts + 1, last_error = ?
           WHERE message_id = ? AND consumer = ?")
    detail message-id (name consumer)]))

(defn dead-letter!
  "This consumer will never accept this message. Record it and stop.

  The other consumers of the same message are untouched, which is the whole
  reason the record is per consumer."
  [datasource schema {:keys [message-id message-kind message-type message-body
                             correlation-id]}
   consumer attempts detail]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute-one!
     tx
     [(str "INSERT INTO " schema ".dead_letter
              (message_id, consumer, message_kind, message_type, message_body,
               correlation_id, attempts, last_error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id, consumer) DO NOTHING")
      message-id (name consumer) message-kind message-type message-body
      correlation-id attempts detail])
    (jdbc/execute-one!
     tx
     [(str "UPDATE " schema ".delivery SET dead = TRUE, attempts = ?, last_error = ?
             WHERE message_id = ? AND consumer = ?")
      attempts detail message-id (name consumer)])))

(defn dead-letters
  [datasource schema]
  (mapv (fn [row] (-> row
                      (update :consumer keyword)
                      (assoc :message (edn/read-string (:message-body row)))))
        (jdbc/execute!
         datasource
         [(str "SELECT message_id, consumer, message_kind, message_type,
                       message_body, attempts, last_error, died_at
                  FROM " schema ".dead_letter ORDER BY died_at")]
         opts)))

(defn revive!
  "Put one consumer's dead letter back on the queue."
  [datasource schema message-id consumer]
  (jdbc/with-transaction [tx datasource]
    (when (jdbc/execute-one!
           tx
           [(str "DELETE FROM " schema ".dead_letter
                   WHERE message_id = ? AND consumer = ? RETURNING message_id")
            message-id (name consumer)])
      (jdbc/execute-one!
       tx
       [(str "UPDATE " schema ".delivery
                 SET dead = FALSE, attempts = 0, last_error = NULL
               WHERE message_id = ? AND consumer = ?")
        message-id (name consumer)])
      (jdbc/execute-one!
       tx
       [(str "UPDATE " schema ".outbox SET published = FALSE WHERE message_id = ?")
        message-id])
      {:revived message-id :consumer consumer})))
