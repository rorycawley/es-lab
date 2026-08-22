(ns lab29.platform.outbox
  "The generic half of every module's outbox.

  A module still owns its tables -- lab 25's rule -- but every outbox now has
  the same shape, so the SQL is written once and told which schema to use.
  That is a deliberate exception to \"the slice owns its SQL\", and the reason
  is that this is not the slice's SQL: an outbox is transport, identical in
  every module, and three copies of it drifting apart would be worse than one
  parameterised copy.

  The schema name is interpolated rather than parameterised because Postgres
  will not take an identifier as a bind parameter, and it comes from this
  file's own callers rather than from anything a request can reach."
  (:require [clojure.edn :as edn]
            [lab29.platform.message :as message]
            [lab29.platform.telemetry :as telemetry]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn enqueue!
  "Record one outgoing message inside the caller's transaction.

  Takes a message built by `message/command` or `message/integration-event`,
  never a bare map, so the kind is decided by whoever knew the intent rather
  than inferred later by whoever has to route it."
  [tx schema msg]
  (when-not (message/valid? msg)
    (throw (ex-info "Refusing to enqueue a malformed message"
                    {:reason :malformed-message
                     :because (message/explain msg)})))
  (jdbc/execute-one!
   tx
   [(str "INSERT INTO " schema ".outbox
            (message_id, message_kind, message_type, message_body,
             correlation_id, traceparent)
          VALUES (?, ?, ?, ?, ?, ?)")
    (:message/id msg)
    (name (:message/kind msg))
    (str (symbol (message/message-type msg)))
    (pr-str msg)
    (get-in msg [:metadata :correlation-id])
    (get (telemetry/trace-headers) "traceparent")]))

(defn enqueue-once!
  "Record a message whose id deliberately identifies the business outcome.

  Unlike `enqueue!`, an existing id is success: independent paths may discover
  the same outcome concurrently. Callers must only use this when the id is
  derived from that outcome, never for an arbitrary generated message id."
  [tx schema msg]
  (when-not (message/valid? msg)
    (throw (ex-info "Refusing to enqueue a malformed message"
                    {:reason :malformed-message
                     :because (message/explain msg)})))
  (jdbc/execute-one!
   tx
   [(str "INSERT INTO " schema ".outbox
            (message_id, message_kind, message_type, message_body,
             correlation_id, traceparent)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (message_id) DO NOTHING")
    (:message/id msg)
    (name (:message/kind msg))
    (str (symbol (message/message-type msg)))
    (pr-str msg)
    (get-in msg [:metadata :correlation-id])
    (get (telemetry/trace-headers) "traceparent")]))

(defn unpublished
  "Messages with work left to do, oldest first."
  [datasource schema]
  (mapv (fn [row] (assoc row :message (edn/read-string (:message-body row))))
        (jdbc/execute!
         datasource
         [(str "SELECT message_id, message_kind, message_type, message_body,
                       correlation_id, traceparent
                  FROM " schema ".outbox
                 WHERE published = FALSE
                 ORDER BY created_order")]
         opts)))

(defn settle!
  "Mark a message published once every consumer is done with it, one way or
  the other. `dead` counts: a consumer that will never accept it is finished
  with it too."
  [datasource schema message-id]
  (jdbc/execute-one!
   datasource
   [(str "UPDATE " schema ".outbox SET published = TRUE
           WHERE message_id = ?
             AND NOT EXISTS (SELECT 1 FROM " schema ".delivery
                              WHERE message_id = ?
                                AND delivered = FALSE AND dead = FALSE)")
    message-id message-id]))
