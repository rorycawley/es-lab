(ns lab20.store
  "Lab 19's Postgres store, unchanged.

  Nothing here knows about an outbox, an inbox or a ledger. Those are separate
  tables written in the *same transaction* as an append — which is the whole
  of this lab, and it needed no change to the store at all."
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->jsonb [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))

(defn- <-jsonb [^PGobject o]
  (when o (json/read-str (.getValue o) :key-fn keyword)))

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
  `truck.clj` is handed exactly what lab 8 handed it — which is why lab 8's
  code runs here untouched."
  [row]
  {:event/id          (:event-id row)
   :event/type        (keyword (:event-type row))
   :event/occurred-at (:occurred-at row)
   :event/position    (:global-position row)
   :stream/id         (:stream-id row)
   :stream/version    (:stream-version row)
   :data              (<-jsonb (:data row))
   :metadata          (assoc (<-jsonb (:metadata row))
                             :recorded-at (:recorded-at row))})

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
  (or (:v (jdbc/execute-one! ds ["SELECT max(stream_version) AS v FROM event
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

  `pg_snapshot_xmin(pg_current_snapshot())` is the oldest transaction still in
  flight. Rows written by anything at or above it may yet be joined by a
  lower-positioned sibling, so they are held back until that cannot happen.

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

(defn- conflict? [^java.sql.SQLException e]
  (= unique-violation (.getSQLState e)))

(defn append
  "Append `events` to `stream-id`, on the condition it is still at
  `expected-version`.

  The condition is not checked here. It is expressed as `UNIQUE (stream_id,
  stream_version)` and enforced by the database — so it holds under real
  concurrency, not merely under the deterministic simulation lab 16 had to
  settle for."
  [ds stream-id expected-version gen-id now command events]
  (jdbc/with-transaction [tx ds]
    (try
      (mapv (fn [i event]
              (row->event
               (jdbc/execute-one!
                tx
                ["INSERT INTO event (event_id, event_type, stream_id, stream_version,
                                     occurred_at, data, metadata)
                  VALUES (?,?,?,?,?,?,?) RETURNING *"
                 (gen-id)
                 (name (:event/type event))
                 stream-id
                 (+ expected-version 1 i)
                 (->timestamp now)
                 (->jsonb (:data event))
                 (->jsonb {:causation-id   (str (:command/id command))
                           :correlation-id (str (:correlation-id command))})]
                opts)))
            (range)
            events)
      (catch java.sql.SQLException e
        (if (conflict? e)
          (throw (ex-info "Concurrent modification of stream"
                          {:stream/id stream-id :expected-version expected-version}))
          (throw e))))))
