(ns lab32.messaging.inbox
  "The consumer's side, and the one table that makes at-least-once survivable.

  Every delivery mechanism above this is at-least-once: the reconciler resends,
  a rolled-back drain re-claims, a crash replays. `uq_compliance_inbox_event`
  is where all of that stops being a problem -- the second copy of an event
  hits the unique constraint and `DO NOTHING` discards it. There is no
  compensating logic anywhere else in the lab, because there does not need to
  be.

  The schema name is interpolated rather than bound, because Postgres will not
  take an identifier as a bind parameter. It comes from the module registry in
  `router.clj`, never from anything a request can reach -- the same exception,
  for the same reason, that lab 29 made for its shared outbox."
  (:require [lab32.db.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn insert!
  "Deliver one message into `schema`'s inbox, **in the caller's transaction**.

  Returns true if this was the first time. Acceptance test 4 cares: a
  redelivery must produce no second row, and this is where it disappears.

  `ON CONFLICT DO NOTHING` and not `DO UPDATE`. An event is immutable, so a
  redelivery carries identical content by definition; there is nothing to
  update, and writing one would overwrite a message the consumer might be
  working on right now.

  Note the absence of `RETURNING`, which is not a style choice -- it is
  migration 004's privilege split showing up in the SQL. `RETURNING` requires
  SELECT on the columns it returns, and `messaging_module` holds INSERT on this
  table and nothing else. Writing the obvious `RETURNING seq` makes the
  dispatcher fail with a permission error, and the fix is not to grant SELECT:
  the transport has no business reading a module's inbox. The update count
  answers the only question it needed answered."
  [tx schema {:keys [event-id event-type partition-key payload]}]
  (pos?
   (or (:next.jdbc/update-count
        (jdbc/execute-one!
         tx
         [(str "INSERT INTO " schema ".inbox (event_id, event_type, partition_key, payload)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING")
          event-id
          (str (symbol event-type))
          (str partition-key)
          (json/->jsonb payload)]
         opts))
       0)))

(defn- row->message
  [row]
  {:seq           (:seq row)
   :event-id      (:event-id row)
   :event-type    (keyword (:event-type row))
   :partition-key (:partition-key row)
   :payload       (:payload row)
   :attempts      (:attempts row)})

(defn claim!
  "Take up to `limit` pending messages, **in the caller's transaction**.

  The worker calls this with a limit of 1 in Phase 1, and that is not
  timidity -- it is how a poison message stops being everyone's problem. The
  claim, the handler and the completion share one transaction, so a handler
  that throws rolls its claim back with it. If the transaction held fifty
  messages, one bad handler would roll back forty-nine innocent ones and they
  would all be retried together, forever. Acceptance test 6 asserts that does
  not happen."
  [tx schema limit]
  (mapv row->message
        (jdbc/execute!
         tx
         [(str "UPDATE " schema ".inbox i
                   SET status = 'PROCESSED', attempts = i.attempts + 1
                  FROM (
                    SELECT seq
                      FROM " schema ".inbox
                     WHERE status = 'PENDING' AND next_attempt_at <= now()
                     ORDER BY seq
                       FOR UPDATE SKIP LOCKED
                     LIMIT ?
                  ) AS claimed
                 WHERE i.seq = claimed.seq
             RETURNING i.*")
          limit]
         opts)))

(defn claim-partition!
  "Phase 3, consumer side. Claim one partition and return everything pending in
  it, in order, **in the caller's transaction**.

  Ordering the delivery into the inbox is only half of it. If the worker then
  takes messages off in any order, or two workers take them concurrently, the
  order the dispatcher went to such trouble to preserve is lost between the
  inbox and the projection.

  One partition rather than several, unlike the outbox side. The difference is
  what happens inside: the dispatcher does local inserts, and this runs a
  module's handler -- arbitrary code of unknown duration. Holding several
  partitions' locks across that is how Gotcha #2's \"keep transactions short\"
  gets violated by a system that looked fine in testing.

  **`ORDER BY random()`, and this is not a style choice.** With
  `ORDER BY partition_key LIMIT 1` every worker picks the same lowest-keyed
  partition, one wins the advisory lock, and the rest claim nothing and
  conclude the queue is empty -- so a scheme built for parallelism runs
  strictly one partition at a time, correctly, and looks like it is working.

  **The `LIMIT 1` is on `locked`, not on `candidates`.** A worker considers
  several partitions and stops at the first advisory lock it actually gets.
  Putting the limit on the candidates instead means a worker whose one pick is
  already held gives up and reports an empty inbox -- which is only a
  probabilistic failure, so it shows up as a concurrency test that fails one
  run in eight rather than as an obvious bug.

  This does not over-lock, and that is worth being precise about because §6.3
  warns about volatile functions under a LIMIT. The executor stops pulling rows
  once the limit is satisfied, so the lock function runs on candidates up to and
  including the first success and on none after it -- and an attempt that
  returns false acquired nothing. Exactly one lock is held, on exactly the
  partition this transaction is about to work.

  See `worker/handle-partition!` for what a failure part-way through means.

  The `AND i.status = 'PENDING'` on the UPDATE is the same non-redundant
  redundancy `outbox/claim-partitions!` explains at length: a predicate that
  lives only in a CTE is not rechecked when a blocked row lock is granted."
  [tx schema]
  (->> (jdbc/execute!
        tx
        [(str "WITH candidates AS (
                 SELECT partition_key
                   FROM " schema ".inbox
                  WHERE status = 'PENDING' AND next_attempt_at <= now()
                  GROUP BY partition_key
                  ORDER BY random()
                  LIMIT 8
               ), locked AS (
                 SELECT partition_key
                   FROM candidates
                  WHERE pg_try_advisory_xact_lock(hashtext(partition_key))
                  LIMIT 1
               ), claimed AS (
                 SELECT i.seq
                   FROM " schema ".inbox i
                   JOIN locked l USING (partition_key)
                  WHERE i.status = 'PENDING' AND i.next_attempt_at <= now()
               )
               UPDATE " schema ".inbox i
                  SET status = 'PROCESSED', attempts = i.attempts + 1
                 FROM claimed c
                WHERE i.seq = c.seq
                  AND i.status = 'PENDING'
            RETURNING i.*")]
        opts)
       (mapv row->message)
       (sort-by :seq)
       vec))

(defn record-failure!
  "Back off one message, and dead-letter it once its budget is spent.

  Its own transaction, for the reason `outbox/record-failure!` gives: the
  attempt counter must outlive the rollback that made it necessary.

  `backoff-seconds` scales the delay and exists so the suite can set it to
  zero. Acceptance test 6 needs a message to exhaust its attempts, and with the
  production base of 1 second that test would spend seven seconds asleep to
  observe something that has nothing to do with time."
  [datasource schema seq-value error {:keys [max-attempts backoff-seconds]
                                      :or   {backoff-seconds 1}}]
  (jdbc/execute-one!
   datasource
   ;; The cast on the CASE is required, and the reason is worth knowing.
   ;; A bare `'FAILED'` is of type `unknown` and Postgres will happily coerce
   ;; it to whatever the assignment target is -- but a CASE expression has to
   ;; settle on one type for its branches *before* the assignment is
   ;; considered, and two unknowns resolve to `text`. So the statement fails
   ;; with "column is of type messaging.msg_status but expression is of type
   ;; text", pointing at the column rather than at the CASE that caused it.
   [(str "UPDATE " schema ".inbox
             SET attempts        = attempts + 1,
                 last_error      = ?,
                 next_attempt_at = now() + (interval '1 second' * ? * power(2, least(attempts, 10))),
                 status          = (CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END)::messaging.msg_status
           WHERE seq = ?")
    error backoff-seconds max-attempts seq-value]))

(defn prune!
  "§7, consumer side. 24 hours of PROCESSED, same as the outbox."
  [datasource schema retention-hours]
  (:next.jdbc/update-count
   (jdbc/execute-one!
    datasource
    [(str "DELETE FROM " schema ".inbox
            WHERE status = 'PROCESSED'
              AND received_at < now() - (interval '1 hour' * ?)")
     retention-hours])))
