(ns lab33.engine.evolve
  "The tempting version: a fold that reads configuration.

  This is lab 0's `models/truck.clj` move. The counter-example is built
  properly rather than described, because the argument against it is only
  worth anything if you can run it and watch the number change.

  And it is genuinely tempting. The fee is a business parameter, it changes
  two or three times a decade, and putting it in configuration is what every
  instinct says to do. `account.clj` differs from this file by one expression.

  What it costs: the same stream folds to a different balance depending on a
  value that is not in the stream. Not *new* events valued differently — the
  balance of an account that has been closed for six years changes because
  somebody edited a file. There is no version to compare, no upcaster to
  write, and lab 17's fold-version check cannot help, because the fold's code
  did not change.

  The failure is also silent in the direction that matters. Nothing throws.
  You get a plausible number, and the only way to notice is to have written
  down the old one."
  (:require [lab33.rules :as rules]))

(def initial-state
  {:status :absent :balance 0M})

(defn evolve
  "`config -> state -> event -> state`.

  Compare the `:money-withdrawn` line with `account/evolve`. That is the whole
  difference: the fee comes from the caller's configuration instead of from
  the fact."
  [config state {:keys [event/type data]}]
  (case type
    :account-opened  (assoc state :status :open :holder (:holder data) :balance 0M)
    :money-deposited (update state :balance + (:amount data))
    :money-withdrawn (update state :balance
                             - (+ (:amount data)
                                  (rules/parameter config :withdrawal-fee)))
    state))

(defn replay
  [config events]
  (reduce (partial evolve config) initial-state events))

(defn balance
  [config events]
  (:balance (replay config events)))
