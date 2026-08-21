(ns lab19.store
  "The event store, in Postgres.

  Compare this with lab 18's in-memory adapter. The domain still consumes plain
  values, while this namespace absorbs SQL, JDBC and serialization details.

  Three storage guarantees live in the database transaction:

    the version check   an atomic stream-head compare-and-set
    the append order    a sequence rather than `(inc (count log))`
    the recorded time   `now()` rather than an application clock

  The sequence brings a problem the in-memory store
  could not have: see `since` and `since-committed`."
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

(defn- ->date
  [value]
  (cond
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (instance? OffsetDateTime value) (Date/from (.toInstant ^OffsetDateTime value))
    :else value))

(def uuid-metadata-keys
  #{:causation-id :correlation-id})

(defn- encode-metadata
  [metadata]
  (reduce (fn [result k]
            (cond-> result
              (uuid? (get result k)) (update k str)))
          (or metadata {})
          uuid-metadata-keys))

(defn- decode-metadata
  [metadata]
  (reduce (fn [result k]
            (let [value (get result k)]
              (cond-> result
                (string? value) (assoc k (UUID/fromString value)))))
          (or metadata {})
          uuid-metadata-keys))

;; ---------------------------------------------------------------------------
;; There is no `restore-types`, and there was.
;;
;; It walked every decoded value looking for fields whose keywords JSON had
;; flattened into strings, against a hand-maintained set of field names. Both
;; are gone, because there are no such fields: what the domain writes into
;; `:data` is already expressible in JSON.
;;
;; Note that `:key-fn keyword` above is a **keys-only** facility, and that
;; asymmetry is the whole of the bug it used to paper over. Encoding a keyword
;; to a string is automatic and silent; decoding cannot undo it, because by
;; then a string is all there is. Keys come back because their names are known
;; in advance. Values are not, so they cannot.
;; ---------------------------------------------------------------------------

(defn- ->timestamp
  "Every lab so far has passed an `#inst`, and every lab so far kept it in a
  vector. JDBC cannot infer a type for `java.util.Date`, so the conversion
  happens here — impedance the adapter absorbs so the caller doesn't have to."
  [^java.util.Date d]
  (java.sql.Timestamp. (.getTime d)))

(defn- row->event
  "Translate a row into the event shape the domain has always seen.

  This is the adapter, and it is the only place that knows both vocabularies.
  `truck.clj` is handed the same plain event values introduced in lab 8, so
  persistence mapping stays outside the domain."
  [row]
  {:event/id          (:event-id row)
   :event/type        (keyword (:event-type row))
   :event/occurred-at (->date (:occurred-at row))
   :event/position    (:global-position row)
   :stream/id         (:stream-id row)
   :stream/version    (:stream-version row)
   :data              (<-jsonb (:data row))
   :metadata          (assoc (decode-metadata (<-jsonb (:metadata row)))
                             :recorded-at (->date (:recorded-at row)))})

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn stream
  "One stream's history, in order (lab 7)."
  [ds stream-id]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event WHERE stream_id = ?
                            ORDER BY stream_version" stream-id]
                       opts)))

(defn current-version
  [ds stream-id]
  (or (:stream-version
       (jdbc/execute-one! ds ["SELECT stream_version FROM stream_head
                               WHERE stream_id = ?" stream-id] opts))
      0))

(defn since
  "Everything after `position`, the obvious way — and the wrong way.

  A `BIGSERIAL` is assigned at INSERT and becomes visible at COMMIT, so a row
  with a lower position can appear *after* one with a higher position. A
  reader that checkpoints on what it can see will step over the gap and never
  come back. `since-committed` is the fix."
  [ds position]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event WHERE global_position > ?
                            ORDER BY global_position" position]
                       opts)))

(defn since-committed
  "Everything after `position` that is definitely settled.

  `pg_snapshot_xmin(pg_current_snapshot())` is the lowest transaction id still
  active in the snapshot. Rows written by anything at or above it may yet be
  joined by a lower-positioned sibling, so they are held back until that
  cannot happen.

  The cost is latency: an event is invisible to readers until every
  transaction that started before it has finished. The benefit is that a
  checkpoint can never step over a gap."
  [ds position]
  (mapv row->event
        (jdbc/execute! ds ["SELECT * FROM event
                            WHERE global_position > ?
                              AND xid < pg_snapshot_xmin(pg_current_snapshot())
                            ORDER BY global_position" position]
                       opts)))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(def ^:private unique-violation "23505")

(defn- constraint-name
  [^java.sql.SQLException e]
  (when (instance? PSQLException e)
    (some-> ^PSQLException e .getServerErrorMessage .getConstraint)))

(defn- unique-violation?
  [^java.sql.SQLException e]
  (= unique-violation (.getSQLState e)))

(defn- find-event
  [connectable event-id]
  (some-> (jdbc/execute-one!
           connectable
           ["SELECT * FROM event WHERE event_id = ?" event-id]
           opts)
          row->event))

(defn- same-recorded-event?
  [stream-id expected-version i proposed recorded]
  (= {:event/id          (:event/id proposed)
      :event/type        (:event/type proposed)
      :event/occurred-at (:event/occurred-at proposed)
      :stream/id         stream-id
      :stream/version    (+ expected-version 1 i)
      :data              (:data proposed)
      :metadata          (or (:metadata proposed) {})}
     (-> recorded
         (select-keys [:event/id :event/type :event/occurred-at
                       :stream/id :stream/version :data :metadata])
         (update :metadata dissoc :recorded-at))))

(defn- matching-retry
  [connectable stream-id expected-version events]
  (when (seq events)
    (let [recorded (mapv #(find-event connectable (:event/id %)) events)]
      (when (and (every? some? recorded)
                 (every? true?
                         (map-indexed
                          (fn [i event]
                            (same-recorded-event? stream-id expected-version i
                                                  event (nth recorded i)))
                          events)))
        recorded))))

(defn- claim-stream!
  [tx stream-id expected-version event-count]
  (jdbc/execute-one!
   tx
   ["WITH updated AS (
       UPDATE stream_head
          SET stream_version = stream_version + ?
        WHERE stream_id = ? AND stream_version = ?
        RETURNING stream_version
     ), inserted AS (
       INSERT INTO stream_head (stream_id, stream_version)
       SELECT ?, ? WHERE ? = 0
       ON CONFLICT DO NOTHING
       RETURNING stream_version
     )
     SELECT stream_version FROM updated
     UNION ALL
     SELECT stream_version FROM inserted"
    event-count
    stream-id
    expected-version
    stream-id
    event-count
    expected-version]
   opts))

(defn- concurrent-modification
  [stream-id expected-version actual-version]
  (ex-info "Concurrent modification of stream"
           {:reason           :concurrent-modification
            :stream/id        stream-id
            :expected-version expected-version
            :actual-version   actual-version}))

(defn- contains-keyword-value?
  [value]
  (cond
    (keyword? value) true
    (map? value) (boolean (some contains-keyword-value? (vals value)))
    (coll? value) (boolean (some contains-keyword-value? value))
    :else false))

(defn- validate-events!
  [events]
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
                      {:reason :lossy-json-value
                       :data (:data event)})))
    (when-not (or (nil? (:metadata event)) (map? (:metadata event)))
      (throw (ex-info "Event metadata must be a map"
                      {:metadata (:metadata event)})))
    (when (contains-keyword-value? (:metadata event))
      (throw (ex-info "Keyword values are not valid stored metadata"
                      {:reason :lossy-json-value
                       :metadata (:metadata event)})))))

(defn append
  "Append `events` to `stream-id`, on the condition it is still at
  `expected-version`.

  The stream-head row is conditionally advanced in the same transaction as the
  inserts. This rejects stale *and future* expected versions. The event-table
  unique constraint remains a final integrity guard.

  Retrying this exact identified batch is idempotent: if all ids already name
  the same intended facts and coordinates, the recorded events are returned."
  [ds stream-id expected-version events]
  (validate-events! events)
  (if (empty? events)
    []
    (or (matching-retry ds stream-id expected-version events)
        (try
          (jdbc/with-transaction [tx ds]
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
                       (:event/id event)
                       (name (:event/type event))
                       stream-id
                       (+ expected-version 1 i)
                       (->timestamp (:event/occurred-at event))
                       (->jsonb (:data event))
                       (->jsonb (encode-metadata (:metadata event)))]
                      opts)))
                  (range)
                  events))
          (catch java.sql.SQLException e
            (if (unique-violation? e)
              (or (matching-retry ds stream-id expected-version events)
                  (if (= "event_id_unique" (constraint-name e))
                    (throw (ex-info "Event id already identifies another fact"
                                    {:reason :duplicate-event-id
                                     :constraint (constraint-name e)}))
                    (throw (concurrent-modification
                            stream-id expected-version (current-version ds stream-id)))))
              (throw e)))))))
