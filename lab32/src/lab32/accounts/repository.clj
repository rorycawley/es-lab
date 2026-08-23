(ns lab32.accounts.repository
  "Accounts' event stream, in Postgres.

  The SQL half of lab 19's store, narrowed. Lab 19 kept a separate
  `stream_head` table and advanced it with a compare-and-set; this lab does not
  need one, because `UNIQUE (aggregate_id, version)` on the stream itself is
  already the compare-and-set. The head row was buying an early, friendlier
  failure. The constraint buys the same guarantee with one less table to keep
  consistent, and `handle!` turns the resulting 23505 back into a retry."
  (:require [lab32.db.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PSQLException)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def aggregate-type "account")

(defn- row->event
  [row]
  {:event/id          (:event-id row)
   :event/type        (keyword (:event-type row))
   :event/occurred-at (:occurred-at row)
   ;; The global sequence. Useful for ordering a *read*, and dangerous as a
   ;; cursor -- see the note on gaps below.
   :event/seq         (:seq row)
   :aggregate/id      (:aggregate-id row)
   :aggregate/type    (:aggregate-type row)
   :aggregate/version (:version row)
   :data              (:data row)
   :metadata          (:metadata row)})

;; ---------------------------------------------------------------------------
;; Gotcha #7 — sequence gaps are real, and this is the comment the spec asks
;; for.
;;
;; `seq` is allocated when the row is INSERTed and becomes visible when the
;; transaction COMMITs, and those are different moments. Two writers can take
;; 4 and 5, and the one holding 5 can commit first. A reader watching the table
;; will see 5, and 4 will appear afterwards -- *behind* a position it has
;; already passed.
;;
;; Everything in this lab is safe from that, because nothing tracks a
;; high-water mark. The dispatcher claims rows by `status = 'PENDING'` and
;; marks each one individually, so a row that appears late is simply a row that
;; is still pending. If anyone ever "optimises" that to `WHERE seq > last_seen`
;; they will silently drop events, the drop rate will scale with concurrency,
;; and no test that runs single-threaded will show it.
;;
;; Lab 19 hit exactly this and documented the alternative, `since-committed`,
;; which holds rows back until `pg_snapshot_xmin(pg_current_snapshot())` proves
;; no lower-numbered sibling can still arrive. That is the fix if you genuinely
;; need a cursor. Not needing one is cheaper.
;; ---------------------------------------------------------------------------

(defn history
  "One aggregate's events, in version order."
  [tx aggregate-id]
  (mapv row->event
        (jdbc/execute! tx ["SELECT * FROM accounts.event_stream
                             WHERE aggregate_id = ?
                             ORDER BY version" aggregate-id]
                       opts)))

(def ^:private unique-violation "23505")

(defn- constraint-of
  [^java.sql.SQLException e]
  (when (instance? PSQLException e)
    (some-> ^PSQLException e .getServerErrorMessage .getConstraint)))

(defn concurrent-write?
  "Did this exception mean another writer got to this version first?

  Specifically the aggregate-version constraint, and not any 23505. A duplicate
  `event_id` is a different bug -- it means a caller reused an identity -- and
  retrying it would loop forever."
  [e]
  (and (instance? java.sql.SQLException e)
       (= unique-violation (.getSQLState ^java.sql.SQLException e))
       (= "uq_accounts_aggregate_version" (constraint-of e))))

(defn append!
  "Append `events` to `aggregate-id`, which must still be at
  `expected-version`.

  Runs inside the caller's transaction on purpose: the whole point of §6.1 is
  that this and the outbox insert commit together or not at all. A version of
  this function that took a datasource and opened its own transaction would
  reintroduce the dual write it exists to prevent, which is why it takes `tx`.

  Note what is *not* passed: `occurred_at`. Lab 19 made the same call and gave
  the reason -- the recorded time is `now()` from the database rather than a
  clock in the application, because a fleet of application clocks disagree with
  each other and a single database clock cannot. `RETURNING *` hands the value
  back, so the caller still learns what time it was."
  [tx aggregate-id expected-version events {:keys [new-id metadata]}]
  (mapv (fn [i event]
          (row->event
           (jdbc/execute-one!
            tx
            ["INSERT INTO accounts.event_stream
                (event_id, aggregate_id, aggregate_type, version,
                 event_type, data, metadata)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              RETURNING *"
             (new-id)
             aggregate-id
             aggregate-type
             (+ expected-version 1 i)
             (str (symbol (:event/type event)))
             (json/->jsonb (:data event))
             (json/->jsonb (or metadata {}))]
            opts)))
        (range)
        events))

;; ---------------------------------------------------------------------------
;; The read-only surface Phase 4 exposes, and the argument it makes.
;;
;; None of this is possible against a log with 24-hour retention and no query
;; language. That is the whole "why not a broker" case, and it is three
;; functions rather than a paragraph.
;; ---------------------------------------------------------------------------

(defn stream
  "Every event for one aggregate, oldest first. `GET /audit/account/:id`."
  [datasource aggregate-id]
  (history datasource aggregate-id))

(defn search
  "Ad-hoc query over the entire history: by type, by time, and by a predicate
  inside the event's own data.

  `min-amount` reaches into JSONB. The GIN index in migration 002 is what keeps
  it from being a sequential scan over everything that ever happened."
  [datasource {:keys [event-type min-amount from until limit]
               :or   {limit 100}}]
  (mapv row->event
        (jdbc/execute!
         datasource
         ["SELECT * FROM accounts.event_stream
            WHERE (?::text IS NULL OR event_type = ?)
              AND (?::numeric IS NULL OR (data->>'amount')::numeric >= ?)
              AND (?::timestamptz IS NULL OR occurred_at >= ?)
              AND (?::timestamptz IS NULL OR occurred_at < ?)
            ORDER BY seq
            LIMIT ?"
          event-type event-type
          min-amount min-amount
          from from
          until until
          limit]
         opts)))

(defn everything
  "The whole stream, oldest first. What a replay reads."
  [datasource]
  (mapv row->event
        (jdbc/execute! datasource ["SELECT * FROM accounts.event_stream ORDER BY seq"]
                       opts)))
