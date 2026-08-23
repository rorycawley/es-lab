(ns lab32.messaging.outbox
  "The producer's side of the transport.

  Two callers, connecting as two different Postgres roles, and the split is the
  design. A module *enqueues* -- inside its own command transaction, which is
  what makes the write path free of a dual write. The dispatcher *claims* and
  *settles*. Neither can do the other's half: `accounts_module` holds INSERT
  and nothing else on this table (migration 003), so a producer physically
  cannot read the queue it writes to."
  (:require [clojure.string :as str]
            [lab32.db.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn enqueue!
  "Record one outgoing integration event **inside the caller's transaction**.

  Taking `tx` rather than a datasource is the entire transactional-outbox
  pattern expressed in a function signature. If this took a datasource it would
  open its own connection, commit separately from the state change, and every
  guarantee in this lab would evaporate -- the state could commit and the event
  not, or the reverse. There is no configuration flag for that failure; there
  is just the type of the first argument."
  [tx {:keys [event-id source-module event-type partition-key payload metadata]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO messaging.outbox
       (event_id, source_module, event_type, partition_key, payload, metadata)
     VALUES (?, ?, ?, ?, ?, ?)"
    event-id
    (name source-module)
    (str (symbol event-type))
    (str partition-key)
    (json/->jsonb payload)
    (json/->jsonb (or metadata {}))]))

(defn enqueue-once!
  "Enqueue a message whose id names a business outcome rather than an attempt.

  Unlike `enqueue!`, finding the id already there is success. Replay uses this:
  the events being re-published are the same facts with the same ids, and any
  that the retention sweep has not yet removed are still sitting in this table.
  Resurrecting those is `requeue!`'s job; this is for the ones that are gone.

  Callers must only use this when the id is derived from the outcome, never for
  a freshly generated message id -- otherwise a genuine collision, which would
  mean two different facts sharing an identity, is silently discarded."
  [tx {:keys [event-id source-module event-type partition-key payload metadata]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO messaging.outbox
       (event_id, source_module, event_type, partition_key, payload, metadata)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT (event_id) DO NOTHING"
    event-id
    (name source-module)
    (str (symbol event-type))
    (str partition-key)
    (json/->jsonb payload)
    (json/->jsonb (or metadata {}))]))

(defn- row->message
  [row]
  {:seq           (:seq row)
   :event-id      (:event-id row)
   :source-module (keyword (:source-module row))
   :event-type    (keyword (:event-type row))
   :partition-key (:partition-key row)
   :payload       (:payload row)
   :metadata      (:metadata row)
   :attempts      (:attempts row)})

(defn claim!
  "Take up to `batch-size` pending messages, marking them PROCESSED as they are
  taken, **in the caller's transaction**.

  Claiming and marking are one statement rather than two, and that is safe only
  because the caller wraps this and the inbox inserts in a single transaction:
  if anything downstream throws, the mark rolls back with everything else and
  the rows are PENDING again. Acceptance test 3 kills the transaction between
  the inbox insert and the commit and asserts exactly that.

  `ORDER BY seq`, not `created_at`. Timestamps collide at this resolution, and
  they are not monotonic across a clock adjustment -- so an NTP correction
  during a busy minute would reorder the queue.

  Note what this does NOT promise: any ordering at all between the messages it
  returns. `SKIP LOCKED` means a concurrent dispatcher takes the rows this one
  stepped over, so two messages for the same account can be worked out of order
  by two threads. That is Gotcha #8 and it is why `:partition` exists."
  [tx batch-size]
  (mapv row->message
        (jdbc/execute!
         tx
         ["UPDATE messaging.outbox o
              SET status = 'PROCESSED', processed_at = now(), attempts = o.attempts + 1
             FROM (
               SELECT seq
                 FROM messaging.outbox
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY seq
                  FOR UPDATE SKIP LOCKED
                LIMIT ?
             ) AS claimed
            WHERE o.seq = claimed.seq
        RETURNING o.*"
          batch-size]
         opts)))

(defn claim-partitions!
  "Phase 3. Claim whole partitions under an advisory lock, **in the caller's
  transaction**, and return their messages in order.

  `claim!` above makes no ordering promise, and cannot: `SKIP LOCKED` means a
  concurrent dispatcher takes the rows this one stepped over, so two movements
  on one account can be delivered out of order by two threads. This claims the
  *account* instead of the row, so everything for that account is worked by one
  dispatcher, in `seq` order, and nobody else can interleave.

  Three details, each of which is the difference between working and looking
  like it works.

  **The LIMIT is in its own CTE, before the lock function.** Putting
  `pg_try_advisory_xact_lock(...)` in a WHERE clause alongside a LIMIT lets the
  planner evaluate the volatile function on rows it then discards -- so the
  transaction holds locks on partitions it never claimed, and other dispatchers
  are blocked out of work nobody is doing. Bounding the candidates first makes
  the lock attempts exactly the ones that count.

  **`pg_try_advisory_xact_lock`, not `pg_advisory_lock`.** The transaction
  variant releases at commit or rollback, so there is no cleanup path and a
  crashed process leaks nothing. The `try` variant returns false rather than
  waiting, so a dispatcher whose partitions are taken moves on to others
  instead of queueing behind them.

  **The ordering is applied in Clojure.** `RETURNING` makes no promise about
  row order -- it returns rows as the update produced them -- so sorting here
  is not belt and braces, it is where the ordering actually comes from.

  **`AND o.status = 'PENDING'` in the UPDATE, not only in the CTE.** This one
  looks redundant and is the least obvious line in the lab. `claimed` selects
  its rows under the statement's snapshot, so a concurrent claimer still sees
  them as PENDING; its UPDATE then blocks on the row lock until the first
  transaction commits. Under READ COMMITTED, Postgres re-evaluates only the
  *UPDATE's own* WHERE clause when the lock is granted -- a predicate that
  lived in a CTE is never rechecked. Without this line the second claimer
  updates rows already marked PROCESSED and returns them, and the same message
  is delivered twice. It cost a flaky ordering test to find, because the
  duplicate delivery is invisible in the read model: the inbox and the
  projection are both keyed on `event_id`, so the second copy is absorbed and
  only the *order* looks wrong.

  `hashtext` maps the key into the 32-bit space the lock table uses, so two
  unrelated partitions will occasionally collide and be serialised against each
  other. That is a throughput cost and never a correctness one, which is the
  right way round."
  [tx {:keys [partition-limit] :or {partition-limit 20}}]
  (->> (jdbc/execute!
        tx
        ["WITH candidates AS (
            SELECT DISTINCT partition_key
              FROM messaging.outbox
             WHERE status = 'PENDING' AND next_attempt_at <= now()
             ORDER BY partition_key
             LIMIT ?
          ), locked AS (
            SELECT partition_key
              FROM candidates
             WHERE pg_try_advisory_xact_lock(hashtext(partition_key))
          ), claimed AS (
            SELECT o.seq
              FROM messaging.outbox o
              JOIN locked l USING (partition_key)
             WHERE o.status = 'PENDING' AND o.next_attempt_at <= now()
          )
          UPDATE messaging.outbox o
             SET status = 'PROCESSED', processed_at = now(), attempts = o.attempts + 1
            FROM claimed c
           WHERE o.seq = c.seq
             AND o.status = 'PENDING'
       RETURNING o.*"
         partition-limit]
        opts)
       (mapv row->message)
       (sort-by (juxt :partition-key :seq))
       vec))

(defn record-failure!
  "Back off one message, and give up on it once it has had enough goes.

  Runs in **its own** transaction, deliberately. The whole reason a failure
  needs recording is that the transaction which was going to do the work rolled
  back -- and an attempt counter written inside that transaction rolls back
  with it, so a message that fails forever would sit at `attempts = 0` forever
  and never reach the dead-letter state.

  The backoff doubles and is capped: `power(2, least(attempts, 10))` seconds,
  so roughly 1s, 2s, 4s ... 1024s, and then every 1024s until the budget is
  spent. `FAILED` rows fall outside the partial index's predicate, which is
  what makes a dead letter cost nothing -- it stops being scanned rather than
  being moved to another table.

  `backoff-seconds` scales the delay, and the suite sets it to zero so that a
  test about exhausting attempts does not become a test about waiting."
  [datasource seq-value error {:keys [max-attempts backoff-seconds]
                               :or   {backoff-seconds 1}}]
  (jdbc/execute-one!
   datasource
   ;; The cast is required. See `inbox/record-failure!` for why a CASE over two
   ;; string literals is `text` rather than `unknown`, and therefore will not
   ;; assign to an enum column.
   ["UPDATE messaging.outbox
        SET attempts        = attempts + 1,
            last_error      = ?,
            next_attempt_at = now() + (interval '1 second' * ? * power(2, least(attempts, 10))),
            status          = (CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END)::messaging.msg_status
      WHERE seq = ?"
    error backoff-seconds max-attempts seq-value]))

(defn requeue!
  "Put already-processed messages of these types back into the queue.

  Half of a replay, and the half the producer cannot do. `event_id` is UNIQUE
  on this table, so re-enqueueing a fact that is still sitting here PROCESSED
  is rejected -- the row has to be resurrected instead. Only the transport has
  the rights to do that, which is why replay is orchestrated by the composition
  root rather than owned by either module.

  Returns how many rows were put back. Zero is a perfectly good answer: once
  the retention sweep has been through, the old rows are gone and the producer
  simply inserts fresh ones."
  [datasource event-types]
  (if (empty? event-types)
    0
    (let [placeholders (str/join ", " (repeat (count event-types) "?"))]
      (or (:next.jdbc/update-count
           (jdbc/execute-one!
            datasource
            (into [(str "UPDATE messaging.outbox
                            SET status = 'PENDING', processed_at = NULL,
                                attempts = 0, next_attempt_at = now()
                          WHERE status = 'PROCESSED'
                            AND event_type IN (" placeholders ")")]
                  (map #(str (symbol %)) event-types))))
          0))))

(defn prune!
  "§7. The outbox is a queue, not an archive: PROCESSED rows older than the
  retention window are deleted.

  Revolut keep 24 hours for the same reason. The rows are not lost -- the facts
  they carried are in `accounts.event_stream`, which is never pruned. Deleting
  a queue entry and deleting history are different acts, and a system that
  cannot tell them apart ends up keeping both forever or losing both."
  [datasource retention-hours]
  (:next.jdbc/update-count
   (jdbc/execute-one!
    datasource
    ["DELETE FROM messaging.outbox
       WHERE status = 'PROCESSED'
         AND processed_at < now() - (interval '1 hour' * ?)"
     retention-hours])))
