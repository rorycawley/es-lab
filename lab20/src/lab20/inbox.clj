(ns lab20.inbox
  "The consumer's side, and the half lab 12 did not have.

  Lab 12's consumer kept a `:seen` set inside its read model. That works, and
  it works for exactly one reason: the effect *was* the read model, so the
  dedupe record and the effect were the same write.

  Move the effect anywhere else — another table, an email, a payment — and the
  `:seen` set protects nothing. What is needed is a record written in the same
  transaction as the effect, so 'I have handled this' and 'I did the thing'
  commit together or not at all. That record is the inbox."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn handled?
  "Has this recipient already dealt with this fact?

  Keyed by the fact's id, never the delivery's — a republished message arrives
  in a new envelope, so deduplicating on `:message/id` would let it through
  (lab 4, and there is a test for the wrong version in lab 12)."
  [ds recipient fact-id]
  (some? (jdbc/execute-one! ds ["SELECT 1 FROM inbox WHERE recipient = ? AND fact_id = ?"
                                (name recipient) fact-id]
                            opts)))

(defn handle-once!
  "Run `effect!` exactly once per fact, per recipient.

  The inbox row and whatever `effect!` writes share one transaction. Either
  both land or neither does; there is no state in which the effect happened
  and the system has forgotten.

  Returns `:handled` or `:already-handled`."
  [ds recipient fact-id effect!]
  (jdbc/with-transaction [tx ds]
    (if (handled? tx recipient fact-id)
      :already-handled
      (do
        (jdbc/execute-one! tx ["INSERT INTO inbox (recipient, fact_id) VALUES (?,?)"
                               (name recipient) fact-id])
        (effect! tx)
        :handled))))

(defn entries
  [ds]
  (jdbc/execute! ds ["SELECT * FROM inbox ORDER BY handled_at, fact_id"] opts))
