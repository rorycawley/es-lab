(ns lab21.adapter.postgres
  "PostgreSQL behind the same command-outcome port as the in-memory fake.

  JSON mapping, optimistic concurrency, idempotency and the transaction that
  joins events to outgoing messages all stop at this adapter boundary."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [lab21.port :as port]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant OffsetDateTime)
           (java.util Date UUID)
           (org.postgresql.util PGobject PSQLException)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->jsonb [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))

(defn- <-jsonb [^PGobject o]
  (when o (json/read-str (.getValue o) :key-fn keyword)))

(defn- canonical-json [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(defn- ->date [value]
  (cond
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (instance? OffsetDateTime value) (Date/from (.toInstant ^OffsetDateTime value))
    :else value))

(defn- ->timestamp [^Date d]
  (java.sql.Timestamp. (.getTime d)))

(def ^:private uuid-metadata-keys #{:causation-id :correlation-id})

(defn- encode-metadata [metadata]
  (reduce (fn [result k]
            (cond-> result (uuid? (get result k)) (update k str)))
          (or metadata {}) uuid-metadata-keys))

(defn- decode-metadata [metadata]
  (reduce (fn [result k]
            (let [value (get result k)]
              (cond-> result (string? value) (assoc k (UUID/fromString value)))))
          (or metadata {}) uuid-metadata-keys))

(defn- row->event [row]
  {:event/id          (:event-id row)
   :event/type        (keyword (:event-type row))
   :event/occurred-at (->date (:occurred-at row))
   :event/position    (:global-position row)
   :stream/id         (:stream-id row)
   :stream/version    (:stream-version row)
   :data              (<-jsonb (:data row))
   :metadata          (assoc (decode-metadata (<-jsonb (:metadata row)))
                             :recorded-at (->date (:recorded-at row)))})

(defn- row->message [row]
  {:message-id     (:message-id row)
   :message-type   (keyword (:message-type row))
   :recipient      (keyword (:recipient row))
   :causation-id   (:causation-id row)
   :correlation-id (:correlation-id row)
   :payload        (<-jsonb (:payload row))})

(defn- contains-keyword-value? [value]
  (cond
    (keyword? value) true
    (map? value) (boolean (some contains-keyword-value? (vals value)))
    (coll? value) (boolean (some contains-keyword-value? value))
    :else false))

(defn- validate-command! [command]
  (when-not (uuid? (:command/id command))
    (throw (ex-info "Invalid command id" {:command/id (:command/id command)})))
  (when-not (uuid? (:correlation-id command))
    (throw (ex-info "Invalid correlation id"
                    {:correlation-id (:correlation-id command)})))
  (when-not (map? (:data command))
    (throw (ex-info "Command data must be a map" {:data (:data command)})))
  (when (contains-keyword-value? (:data command))
    (throw (ex-info "Keyword values are not valid command data"
                    {:reason :lossy-json-value :data (:data command)}))))

(defn- validate-outcome! [events messages]
  (when-not (= (count events) (count (distinct (map :event/id events))))
    (throw (ex-info "Duplicate event ids inside outcome"
                    {:reason :duplicate-event-id})))
  (when-not (= (count messages) (count (distinct (map :message-id messages))))
    (throw (ex-info "Duplicate message ids inside outcome"
                    {:reason :duplicate-message-id})))
  (doseq [event events]
    (when-not (and (uuid? (:event/id event))
                   (keyword? (:event/type event))
                   (inst? (:event/occurred-at event))
                   (map? (:data event)))
      (throw (ex-info "Invalid event proposal" {:event event})))
    (when (contains-keyword-value? (:data event))
      (throw (ex-info "Keyword values are not valid stored data"
                      {:reason :lossy-json-value :data (:data event)})))
    (when-not (or (nil? (:metadata event)) (map? (:metadata event)))
      (throw (ex-info "Event metadata must be a map"
                      {:metadata (:metadata event)})))
    (when (contains-keyword-value? (:metadata event))
      (throw (ex-info "Keyword values are not valid stored metadata"
                      {:reason :lossy-json-value :metadata (:metadata event)}))))
  (doseq [message messages]
    (when-not (and (uuid? (:message-id message))
                   (uuid? (:causation-id message))
                   (uuid? (:correlation-id message))
                   (keyword? (:message-type message))
                   (keyword? (:recipient message))
                   (map? (:payload message)))
      (throw (ex-info "Invalid message proposal" {:message message})))
    (when (contains-keyword-value? (:payload message))
      (throw (ex-info "Keyword values are not valid message data"
                      {:reason :lossy-json-value :payload (:payload message)}))))
  true)

(defn- current-version [connectable stream-id]
  (or (:stream-version
       (jdbc/execute-one! connectable
                          ["SELECT stream_version FROM stream_head WHERE stream_id = ?"
                           stream-id] opts))
      0))

(defn- ledger-entry [connectable command-id]
  (some-> (jdbc/execute-one!
           connectable
           ["SELECT * FROM command_ledger WHERE command_id = ?" command-id]
           opts)
          (update :command-data <-jsonb)))

(defn- assert-same-command! [entry stream-id command]
  (when-not (= {:stream-id stream-id
                :command-type (name (:command/type command))
                :data (canonical-json (:data command))}
               {:stream-id (:stream-id entry)
                :command-type (:command-type entry)
                :data (:command-data entry)})
    (throw (ex-info "Command id already identifies another request"
                    {:reason :command-id-collision
                     :command/id (:command/id command)})))
  entry)

(defn- events-caused-by [connectable stream-id command-id]
  (mapv row->event
        (jdbc/execute! connectable
                       ["SELECT * FROM event
                         WHERE stream_id = ? AND metadata->>'causation-id' = ?
                         ORDER BY stream_version"
                        stream-id (str command-id)] opts)))

(defn- prior-result [connectable stream-id command]
  (when-let [entry (ledger-entry connectable (:command/id command))]
    (assert-same-command! entry stream-id command)
    (let [events (events-caused-by connectable stream-id (:command/id command))]
      (when-not (= (:event-count entry) (count events))
        (throw (ex-info "Command ledger does not match recorded outcome"
                        {:reason :corrupt-command-ledger
                         :command/id (:command/id command)})))
      events)))

(defn- claim-stream! [tx stream-id expected-version event-count]
  (jdbc/execute-one!
   tx
   ["WITH updated AS (
       UPDATE stream_head SET stream_version = stream_version + ?
        WHERE stream_id = ? AND stream_version = ? RETURNING stream_version
     ), inserted AS (
       INSERT INTO stream_head (stream_id, stream_version)
       SELECT ?, ? WHERE ? = 0 ON CONFLICT DO NOTHING RETURNING stream_version
     )
     SELECT stream_version FROM updated UNION ALL SELECT stream_version FROM inserted"
    event-count stream-id expected-version stream-id event-count expected-version]
   opts))

(defn- concurrent-modification [connectable stream-id expected-version]
  (ex-info "Concurrent modification of stream"
           {:reason :concurrent-modification
            :stream/id stream-id
            :expected-version expected-version
            :actual-version (current-version connectable stream-id)}))

(defn- insert-outcome! [tx stream-id expected-version command events messages]
  (when (nil? (claim-stream! tx stream-id expected-version (count events)))
    (throw (concurrent-modification tx stream-id expected-version)))
  (let [recorded
        (mapv (fn [i event]
                (row->event
                 (jdbc/execute-one!
                  tx
                  ["INSERT INTO event (event_id, event_type, stream_id, stream_version,
                                       occurred_at, data, metadata)
                    VALUES (?,?,?,?,?,?,?) RETURNING *"
                   (:event/id event) (name (:event/type event)) stream-id
                   (+ expected-version 1 i) (->timestamp (:event/occurred-at event))
                   (->jsonb (:data event))
                   (->jsonb (encode-metadata (:metadata event)))] opts)))
              (range) events)]
    (doseq [message messages]
      (jdbc/execute-one!
       tx
       ["INSERT INTO outbox
           (message_id, message_type, recipient, causation_id, correlation_id, payload)
         VALUES (?,?,?,?,?,?)"
        (:message-id message) (name (:message-type message))
        (name (:recipient message)) (:causation-id message)
        (:correlation-id message) (->jsonb (:payload message))]
       opts))
    (jdbc/execute-one!
     tx
     ["INSERT INTO command_ledger
         (command_id, stream_id, command_type, correlation_id, command_data, event_count)
       VALUES (?,?,?,?,?,?)"
      (:command/id command) stream-id (name (:command/type command))
      (:correlation-id command) (->jsonb (:data command)) (count recorded)]
     opts)
    recorded))

(defrecord PostgresStore [datasource]
  port/EventStore
  (command-result [_ stream-id command]
    (validate-command! command)
    (prior-result datasource stream-id command))

  (read-stream [_ stream-id]
    (mapv row->event
          (jdbc/execute! datasource
                         ["SELECT * FROM event WHERE stream_id = ? ORDER BY stream_version"
                          stream-id] opts)))

  (stream-version [_ stream-id]
    (current-version datasource stream-id))

  (read-since [_ position]
    (mapv row->event
          (jdbc/execute! datasource
                         ["SELECT * FROM event WHERE global_position > ?
                             AND xid < pg_snapshot_xmin(pg_current_snapshot())
                           ORDER BY global_position" position] opts)))

  (commit-command [_ stream-id expected-version command events messages]
    (validate-command! command)
    (validate-outcome! events messages)
    (or (prior-result datasource stream-id command)
        (try
          (jdbc/with-transaction [tx datasource]
            (or (prior-result tx stream-id command)
                (insert-outcome! tx stream-id expected-version command events messages)))
          (catch Exception failure
            (or (prior-result datasource stream-id command)
                (let [constraint (when (instance? PSQLException failure)
                                   (some-> ^PSQLException failure
                                           .getServerErrorMessage .getConstraint))]
                  (case constraint
                    "event_id_unique"
                    (throw (ex-info "Event id already identifies another fact"
                                    {:reason :duplicate-event-id}))
                    "outbox_message_id_unique"
                    (throw (ex-info "Message id already identifies another envelope"
                                    {:reason :duplicate-message-id}))
                    (throw failure))))))))

  port/Outbox
  (pending [_]
    (mapv row->message
          (jdbc/execute! datasource
                         ["SELECT * FROM outbox WHERE sent_at IS NULL ORDER BY id"]
                         opts))))

(defrecord Database [config datasource]
  component/Lifecycle
  (start [this]
    (let [ds (jdbc/get-datasource config)]
      (doseq [statement (->> (slurp (io/resource "schema.sql"))
                             (#(str/replace % #"(?m)--.*$" ""))
                             (re-seq #"(?s)CREATE[^;]+;"))]
        (jdbc/execute! ds [statement]))
      (assoc this :datasource ds)))
  (stop [this] (assoc this :datasource nil)))

(defn database [config] (map->Database {:config config}))
(defn store [] (map->PostgresStore {}))
