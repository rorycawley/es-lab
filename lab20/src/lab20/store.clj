(ns lab20.store
  "Lab 19's Postgres adapter with a transaction-local append, so Lab20 can
  commit events, command ledger and outbox as one unit."
  (:require [clojure.data.json :as json]
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

(defn- ->date [value]
  (cond
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (instance? OffsetDateTime value) (Date/from (.toInstant ^OffsetDateTime value))
    :else value))

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

(defn- ->timestamp [^Date d]
  (java.sql.Timestamp. (.getTime d)))

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

(defn stream [ds stream-id]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event WHERE stream_id = ?
                            ORDER BY stream_version" stream-id] opts)))

(defn current-version [ds stream-id]
  (or (:stream-version
       (jdbc/execute-one! ds ["SELECT stream_version FROM stream_head
                               WHERE stream_id = ?" stream-id] opts))
      0))

(defn since
  "The tempting global-position query; it can skip a lower value allocated by
  a transaction that commits after a higher one."
  [ds position]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event WHERE global_position > ?
                            ORDER BY global_position" position] opts)))

(defn since-committed
  "Hold back positions at or above the oldest active transaction, preventing
  a durable checkpoint from stepping over an in-flight gap."
  [ds position]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event
                            WHERE global_position > ?
                              AND xid < pg_snapshot_xmin(pg_current_snapshot())
                            ORDER BY global_position" position] opts)))

(defn- contains-keyword-value? [value]
  (cond
    (keyword? value) true
    (map? value) (boolean (some contains-keyword-value? (vals value)))
    (coll? value) (boolean (some contains-keyword-value? value))
    :else false))

(defn- validate-events! [events]
  (when-not (= (count events) (count (distinct (map :event/id events))))
    (throw (ex-info "Duplicate event ids inside append batch"
                    {:reason :duplicate-event-id})))
  (doseq [event events]
    (when-not (uuid? (:event/id event))
      (throw (ex-info "Invalid event id" {:event/id (:event/id event)})))
    (when-not (keyword? (:event/type event))
      (throw (ex-info "Invalid event type" {:event/type (:event/type event)})))
    (when-not (inst? (:event/occurred-at event))
      (throw (ex-info "Invalid occurred-at instant"
                      {:event/occurred-at (:event/occurred-at event)})))
    (when-not (map? (:data event))
      (throw (ex-info "Event data must be a map" {:data (:data event)})))
    (when (contains-keyword-value? (:data event))
      (throw (ex-info "Keyword values are not valid stored data"
                      {:reason :lossy-json-value :data (:data event)})))
    (when-not (or (nil? (:metadata event)) (map? (:metadata event)))
      (throw (ex-info "Event metadata must be a map"
                      {:metadata (:metadata event)})))
    (when (contains-keyword-value? (:metadata event))
      (throw (ex-info "Keyword values are not valid stored metadata"
                      {:reason :lossy-json-value :metadata (:metadata event)})))))

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

(defn- concurrent-modification [stream-id expected-version actual-version]
  (ex-info "Concurrent modification of stream"
           {:reason :concurrent-modification
            :stream/id stream-id
            :expected-version expected-version
            :actual-version actual-version}))

(defn append-in-transaction!
  "Append identified events inside the caller's transaction. Persistence adds
  only stream coordinates and recorded time; it never mints fact identity."
  [tx stream-id expected-version events]
  (validate-events! events)
  (if (empty? events)
    []
    (do
      (when-not (claim-stream! tx stream-id expected-version (count events))
        (throw (concurrent-modification
                stream-id expected-version (current-version tx stream-id))))
      (mapv (fn [i event]
              (row->event
               (jdbc/execute-one!
                tx
                ["INSERT INTO event (event_id, event_type, stream_id,
                                     stream_version, occurred_at, data, metadata)
                  VALUES (?,?,?,?,?,?,?) RETURNING *"
                 (:event/id event) (name (:event/type event)) stream-id
                 (+ expected-version 1 i) (->timestamp (:event/occurred-at event))
                 (->jsonb (:data event))
                 (->jsonb (encode-metadata (:metadata event)))] opts)))
            (range) events))))

(def ^:private unique-violation "23505")

(defn- constraint-name [^java.sql.SQLException e]
  (when (instance? PSQLException e)
    (some-> ^PSQLException e .getServerErrorMessage .getConstraint)))

(defn- find-event [connectable event-id]
  (some-> (jdbc/execute-one! connectable
                             ["SELECT * FROM event WHERE event_id = ?" event-id] opts)
          row->event))

(defn- matching-retry [connectable stream-id expected-version events]
  (when (seq events)
    (let [recorded (mapv #(find-event connectable (:event/id %)) events)
          intended (fn [i event]
                     {:event/id (:event/id event)
                      :event/type (:event/type event)
                      :event/occurred-at (:event/occurred-at event)
                      :stream/id stream-id
                      :stream/version (+ expected-version 1 i)
                      :data (:data event)
                      :metadata (or (:metadata event) {})})
          actual (fn [event]
                   (-> event
                       (select-keys [:event/id :event/type :event/occurred-at
                                     :stream/id :stream/version :data :metadata])
                       (update :metadata dissoc :recorded-at)))]
      (when (and (every? some? recorded)
                 (every? true? (map-indexed #(= (intended %1 %2)
                                                (actual (nth recorded %1)))
                                            events)))
        recorded))))

(defn append
  "Standalone adapter operation. Exact identified retries return their
  recorded rows; stale and future expected versions are rejected."
  [ds stream-id expected-version events]
  (validate-events! events)
  (if (empty? events)
    []
    (or (matching-retry ds stream-id expected-version events)
        (try
          (jdbc/with-transaction [tx ds]
            (append-in-transaction! tx stream-id expected-version events))
          (catch java.sql.SQLException e
            (if (= unique-violation (.getSQLState e))
              (or (matching-retry ds stream-id expected-version events)
                  (if (= "event_id_unique" (constraint-name e))
                    (throw (ex-info "Event id already identifies another fact"
                                    {:reason :duplicate-event-id}))
                    (throw (concurrent-modification
                            stream-id expected-version (current-version ds stream-id)))))
              (throw e)))))))
