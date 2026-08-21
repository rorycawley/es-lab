(ns lab20.inbox
  "The consumer's side, and the half lab 12 did not have.

  Lab 12's consumer kept a `:seen` set inside its read model. That works, and
  it works for exactly one reason: the effect *was* the read model, so the
  dedupe record and the effect were the same write.

  For an effect in the same database, the inbox record and effect can share a
  transaction. An email, payment or other remote effect cannot join that
  transaction and needs its own idempotency or outbox boundary."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- recipient-name [recipient]
  (if (keyword? recipient) (name recipient) recipient))

(defn handled?
  "Has this recipient already dealt with this fact?

  Keyed by the fact's id, never the delivery's — a republished message arrives
  in a new envelope, so deduplicating on `:message/id` would let it through
  (lab 4, and there is a test for the wrong version in lab 12)."
  [ds recipient fact-id]
  (some? (jdbc/execute-one! ds ["SELECT 1 FROM inbox WHERE recipient = ? AND fact_id = ?"
                                (recipient-name recipient) fact-id]
                            opts)))

(defn handle-once-in-transaction!
  "Claim a fact atomically and run a local database effect in the caller's
  transaction. `ON CONFLICT` removes the check-then-insert race."
  [tx recipient fact-id effect!]
  (if (jdbc/execute-one!
       tx ["INSERT INTO inbox (recipient, fact_id) VALUES (?,?)
            ON CONFLICT DO NOTHING RETURNING recipient"
           (recipient-name recipient) fact-id]
       opts)
    (do (effect! tx) :handled)
    :already-handled))

(defn handle-once!
  "Run `effect!` exactly once per fact, per recipient.

  The inbox row and whatever `effect!` writes share one transaction. Either
  both land or neither does; there is no state in which the effect happened
  and the system has forgotten.

  Returns `:handled` or `:already-handled`."
  [ds recipient fact-id effect!]
  (jdbc/with-transaction [tx ds]
    (handle-once-in-transaction! tx recipient fact-id effect!)))

(defn entries
  [ds]
  (jdbc/execute! ds ["SELECT * FROM inbox ORDER BY handled_at, fact_id"] opts))
