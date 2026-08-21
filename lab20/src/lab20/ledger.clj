(ns lab20.ledger
  "The command ledger — and the hole in lab 10 it exists to close.

  Lab 10 deduplicated a command by asking whether any event carried its
  causation id:

      (if (store/caused-by? log (:command/id command)) …)

  That works while every command produces at least one event. [Lab 5]
  established that producing *none* is a legitimate outcome, and for such a
  command `caused-by?` answers false forever — so it runs again on every pass,
  and again, and again.

  The archive's ADR-0004 states the rule directly: causation and correlation
  are traceability metadata, not idempotency keys. Idempotency belongs in a
  ledger keyed by command id, whose row is written whether the command
  produced three events, one, or none."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn recorded?
  [ds command-id]
  (some? (jdbc/execute-one! ds ["SELECT 1 FROM command_ledger WHERE command_id = ?"
                                command-id]
                            opts)))

(defn record!
  "Note that a command ran, and how many events it produced.

  Called inside the command's own transaction, so the ledger row and the
  events land together."
  [tx command event-count]
  (jdbc/execute-one!
   tx ["INSERT INTO command_ledger (command_id, command_type, event_count) VALUES (?,?,?)"
       (:command/id command) (name (:command/type command)) event-count]
   opts))

(defn entry
  [ds command-id]
  (jdbc/execute-one! ds ["SELECT * FROM command_ledger WHERE command_id = ?" command-id]
                     opts))
