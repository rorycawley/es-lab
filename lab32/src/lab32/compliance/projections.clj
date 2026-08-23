(ns lab32.compliance.projections
  "Compliance's read model, and the rule that decides what goes in it.

  Lab 9's definition still applies: this table is derived, it is not
  authoritative, and dropping and rebuilding it must not change any answer the
  system gives. `/audit/replay/compliance` does exactly that, and
  `replay_test.clj` compares the rebuilt table against the original row for
  row."
  (:require [lab32.money :as money]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def threshold
  "The reporting threshold: a single movement strictly greater than this is
  flagged.

  A BigDecimal, and comparing it against a Double would be the exact bug
  `money.clj` exists to prevent -- `10000.01` as a double is not
  `10000.01`, and a threshold test is precisely where that shows up."
  10000M)

(defn flagged?
  [amount]
  (pos? (compare (money/of amount) threshold)))

(defn flag!
  "Record one flagged movement, **in the caller's transaction**.

  `ON CONFLICT DO NOTHING` on top of the inbox's own unique constraint, which
  looks like belt and braces and is not. The inbox stops a duplicate
  *delivery*; this stops a duplicate *projection* -- a replay, a redriven dead
  letter, a handler that ran twice for a reason nobody predicted. They are
  different sources of the same duplicate, and the cheap defence is that
  `event_id` is already the natural key."
  [tx event-id {:keys [account-id amount direction]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO compliance.flagged_transactions
       (event_id, account_id, amount, direction)
     VALUES (?, ?, ?, ?)
     ON CONFLICT (event_id) DO NOTHING"
    event-id
    (UUID/fromString account-id)
    (money/of amount)
    direction]))

(defn handle-transaction-recorded!
  "The handler the inbox worker runs, inside the worker's transaction.

  Most messages produce no write at all, and that is normal -- a consumer that
  decides an event is not interesting has still handled it. The inbox row is
  marked PROCESSED either way, because \"I looked and there was nothing to do\"
  and \"I have not looked yet\" must not be the same state."
  [tx {:keys [event-id payload]}]
  (when (flagged? (:amount payload))
    (flag! tx event-id payload)))

(defn flagged-transactions
  ([datasource] (flagged-transactions datasource nil))
  ([datasource account-id]
   (mapv (fn [row]
           (-> row
               (update :amount money/of)
               (update :flagged-at #(some-> ^java.sql.Timestamp % .toInstant))))
         (jdbc/execute!
          datasource
          ["SELECT * FROM compliance.flagged_transactions
             WHERE (?::uuid IS NULL OR account_id = ?)
             ORDER BY flagged_at, event_id"
           account-id account-id]
          opts))))

(defn clear!
  "Empty the read model and forget which events built it.

  Both halves, and the second is the one that is easy to miss. Truncating only
  the projection and re-sending the events would achieve nothing: the inbox's
  UNIQUE constraint would discard every redelivery as a duplicate and the table
  would stay empty. Idempotency defends against accidental repetition, which
  means a deliberate rebuild has to say so explicitly."
  [datasource]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx ["TRUNCATE compliance.flagged_transactions"])
    (jdbc/execute! tx ["DELETE FROM compliance.inbox"])))
