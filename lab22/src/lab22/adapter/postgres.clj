(ns lab22.adapter.postgres
  "PostgreSQL adapter for the atomic command-outcome port. Malli decoding is
  confined here, where JSON crosses back into application values."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [lab22.port :as port]
            [lab22.schema.event :as event-schema]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant OffsetDateTime)
           (java.util Date)
           (org.postgresql.util PGobject PSQLException)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- ->jsonb [x] (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))
(defn- <-jsonb [^PGobject x] (when x (json/read-str (.getValue x) :key-fn keyword)))
(defn- canonical [x] (json/read-str (json/write-str x) :key-fn keyword))
(defn- ->timestamp [^Date d] (java.sql.Timestamp. (.getTime d)))
(defn- ->date [x]
  (cond (instance? Date x) x
        (instance? Instant x) (Date/from x)
        (instance? OffsetDateTime x) (Date/from (.toInstant ^OffsetDateTime x))
        :else x))

(defn- keyword-value? [x]
  (cond (keyword? x) true
        (map? x) (boolean (some keyword-value? (vals x)))
        (coll? x) (boolean (some keyword-value? x))
        :else false))

(defn- encode-uuid-values [m]
  (reduce-kv (fn [result k v] (assoc result k (if (uuid? v) (str v) v))) {} (or m {})))

(defn- row->event [row]
  (let [event-type (keyword (:event-type row))]
    {:event/id (:event-id row)
     :event/type event-type
     :event/occurred-at (->date (:occurred-at row))
     :event/position (:global-position row)
     :stream/id (:stream-id row)
     :stream/version (:stream-version row)
     :data (event-schema/decode-data event-type (<-jsonb (:data row)))
     :metadata (assoc (event-schema/decode-metadata (<-jsonb (:metadata row)))
                      :recorded-at (->date (:recorded-at row)))}))

(defn- row->message [row]
  {:message-id (:message-id row)
   :message-type (keyword (:message-type row))
   :recipient (keyword (:recipient row))
   :causation-id (:causation-id row)
   :correlation-id (:correlation-id row)
   :payload (<-jsonb (:payload row))})

(defn- validate! [command events messages]
  (when-not (and (uuid? (:command/id command)) (uuid? (:correlation-id command))
                 (map? (:data command)) (not (keyword-value? (:data command))))
    (throw (ex-info "Invalid command" {:command command})))
  (when-not (= (count events) (count (distinct (map :event/id events))))
    (throw (ex-info "Duplicate event ids" {:reason :duplicate-event-id})))
  (when-not (= (count messages) (count (distinct (map :message-id messages))))
    (throw (ex-info "Duplicate message ids" {:reason :duplicate-message-id})))
  (doseq [event events]
    (when-not (and (uuid? (:event/id event)) (keyword? (:event/type event))
                   (inst? (:event/occurred-at event)) (map? (:data event))
                   (or (nil? (:metadata event)) (map? (:metadata event)))
                   (event-schema/valid-data? (:event/type event) (:data event))
                   (not (keyword-value? (:data event)))
                   (not (keyword-value? (:metadata event))))
      (throw (ex-info "Invalid event proposal" {:event event}))))
  (doseq [message messages]
    (when-not (and (uuid? (:message-id message)) (uuid? (:causation-id message))
                   (uuid? (:correlation-id message)) (keyword? (:message-type message))
                   (keyword? (:recipient message)) (map? (:payload message))
                   (not (keyword-value? (:payload message))))
      (throw (ex-info "Invalid message proposal" {:message message})))))

(defn- current-version [db stream-id]
  (or (:stream-version (jdbc/execute-one!
                        db ["SELECT stream_version FROM stream_head WHERE stream_id = ?"
                            stream-id] opts)) 0))

(defn- ledger-entry [db command-id]
  (some-> (jdbc/execute-one! db ["SELECT * FROM command_ledger WHERE command_id = ?"
                                 command-id] opts)
          (update :command-data <-jsonb)))

(defn- assert-same! [entry stream-id command]
  (when-not (= {:stream-id stream-id :command-type (name (:command/type command))
                :data (canonical (:data command))}
               {:stream-id (:stream-id entry) :command-type (:command-type entry)
                :data (:command-data entry)})
    (throw (ex-info "Command id already identifies another request"
                    {:reason :command-id-collision})))
  entry)

(defn- caused-events [db stream-id command-id]
  (mapv row->event
        (jdbc/execute! db ["SELECT * FROM event
                            WHERE stream_id = ? AND metadata->>'causation-id' = ?
                            ORDER BY stream_version" stream-id (str command-id)] opts)))

(defn- prior-result [db stream-id command]
  (when-let [entry (ledger-entry db (:command/id command))]
    (assert-same! entry stream-id command)
    (let [events (caused-events db stream-id (:command/id command))]
      (when-not (= (:event-count entry) (count events))
        (throw (ex-info "Command ledger does not match its events"
                        {:reason :corrupt-command-ledger})))
      events)))

(defn- claim! [tx stream-id expected n]
  (jdbc/execute-one!
   tx ["WITH updated AS (
          UPDATE stream_head SET stream_version = stream_version + ?
           WHERE stream_id = ? AND stream_version = ? RETURNING stream_version
        ), inserted AS (
          INSERT INTO stream_head (stream_id, stream_version)
          SELECT ?, ? WHERE ? = 0 ON CONFLICT DO NOTHING RETURNING stream_version)
        SELECT stream_version FROM updated UNION ALL SELECT stream_version FROM inserted"
       n stream-id expected stream-id n expected] opts))

(defn- insert-outcome! [tx stream-id expected command events messages]
  (when-not (claim! tx stream-id expected (count events))
    (throw (ex-info "Concurrent modification of stream"
                    {:reason :concurrent-modification :stream/id stream-id
                     :expected-version expected :actual-version (current-version tx stream-id)})))
  (let [recorded (mapv
                  (fn [i event]
                    (row->event
                     (jdbc/execute-one!
                      tx ["INSERT INTO event
                            (event_id,event_type,stream_id,stream_version,occurred_at,data,metadata)
                           VALUES (?,?,?,?,?,?,?) RETURNING *"
                          (:event/id event) (name (:event/type event)) stream-id
                          (+ expected 1 i) (->timestamp (:event/occurred-at event))
                          (->jsonb (:data event))
                          (->jsonb (encode-uuid-values (:metadata event)))] opts)))
                  (range) events)]
    (doseq [message messages]
      (jdbc/execute-one!
       tx ["INSERT INTO outbox
             (message_id,message_type,recipient,causation_id,correlation_id,payload)
            VALUES (?,?,?,?,?,?)"
           (:message-id message) (name (:message-type message)) (name (:recipient message))
           (:causation-id message) (:correlation-id message) (->jsonb (:payload message))] opts))
    (jdbc/execute-one!
     tx ["INSERT INTO command_ledger
           (command_id,stream_id,command_type,correlation_id,command_data,event_count)
          VALUES (?,?,?,?,?,?)"
         (:command/id command) stream-id (name (:command/type command))
         (:correlation-id command) (->jsonb (:data command)) (count recorded)] opts)
    recorded))

(defn- constraint [failure]
  (when (instance? PSQLException failure)
    (some-> ^PSQLException failure .getServerErrorMessage .getConstraint)))

(defrecord PostgresStore [datasource]
  port/EventStore
  (command-result [_ stream-id command]
    (validate! command [] [])
    (prior-result datasource stream-id command))
  (read-stream [_ stream-id]
    (mapv row->event (jdbc/execute! datasource
                                    ["SELECT * FROM event WHERE stream_id = ?
                                      ORDER BY stream_version" stream-id] opts)))
  (stream-version [_ stream-id] (current-version datasource stream-id))
  (read-since [_ position]
    (mapv row->event (jdbc/execute! datasource
                                    ["SELECT * FROM event WHERE global_position > ?
                                        AND xid < pg_snapshot_xmin(pg_current_snapshot())
                                      ORDER BY global_position" position] opts)))
  (commit-command [_ stream-id expected command events messages]
    (validate! command events messages)
    (or (prior-result datasource stream-id command)
        (try
          (jdbc/with-transaction [tx datasource]
            (or (prior-result tx stream-id command)
                (insert-outcome! tx stream-id expected command events messages)))
          (catch Exception failure
            (or (prior-result datasource stream-id command)
                (case (constraint failure)
                  "event_id_unique" (throw (ex-info "Duplicate event id"
                                                    {:reason :duplicate-event-id}))
                  "outbox_message_id_unique" (throw (ex-info "Duplicate message id"
                                                             {:reason :duplicate-message-id}))
                  (throw failure)))))))
  port/Outbox
  (pending [_]
    (mapv row->message (jdbc/execute! datasource
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
