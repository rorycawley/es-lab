(ns lab20.ledger
  "Command idempotency independent of event production.

  The ledger stores enough request identity to distinguish an exact retry from
  accidental reuse of a command id. Causation remains traceability metadata."
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->jsonb [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))

(defn- <-jsonb [^PGobject o]
  (when o (json/read-str (.getValue o) :key-fn keyword)))

(defn entry [ds command-id]
  (some-> (jdbc/execute-one!
           ds ["SELECT * FROM command_ledger WHERE command_id = ?" command-id]
           opts)
          (update :command-data <-jsonb)))

(defn assert-same-command!
  "Return the entry for the same business request; reject command-id reuse
  with a different target, type or data. Correlation remains trace metadata."
  [entry stream-id command]
  (let [recorded {:stream-id (:stream-id entry)
                  :command-type (:command-type entry)
                  :data (:command-data entry)}
        attempted {:stream-id stream-id
                   :command-type (name (:command/type command))
                   :data (:data command)}]
    (when-not (= recorded attempted)
      (throw (ex-info "Command id already identifies another request"
                      {:reason :command-id-collision
                       :command/id (:command/id command)})))
    entry))

(defn record!
  "Record a handled request in the same transaction as its events and outbox."
  [tx stream-id command event-count]
  (jdbc/execute-one!
   tx
   ["INSERT INTO command_ledger
       (command_id, stream_id, command_type, correlation_id, command_data, event_count)
     VALUES (?,?,?,?,?,?)"
    (:command/id command) stream-id (name (:command/type command))
    (:correlation-id command) (->jsonb (:data command)) event-count]
   opts))
