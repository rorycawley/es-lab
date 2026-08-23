(ns lab33.policy
  "`react : config -> event -> [command]`. The best home for configuration.

  Lab 10 already contains the reason, stated for a different purpose: *a
  policy owns the reaction; `decide` protects the target.* That is precisely
  what makes a configured policy safe, and it is two properties rather than
  one.

  **A policy's output is a request.** Misconfigure it and it asks for
  something wrong; the target aggregate's `decide` then refuses on its own
  authority. The blast radius of a bad number here is a refused command, not a
  corrupted history — `policy_test.clj` misconfigures the sweep to an amount
  the savings account cannot cover and asserts the history is untouched.

  **A policy is forward-only.** Changing it today alters no recorded fact.
  There is nothing to replay and therefore nothing to disagree with, which is
  the property `evolve` cannot have and the reason the two get opposite
  answers to the same question."
  (:import (java.util UUID)))

(defn derived-command-id
  "Lab 10's derivation, and one rule about it that only matters once the policy
  is configurable.

  The id is derived from the policy's **name and the triggering event** —
  never from the configured values. A reactor is fed by at-least-once
  delivery, so the same event arrives twice and the second command must be
  recognisable as a repeat.

  Fold a parameter into this and reconfiguring silently breaks that: the
  redelivery of an old event produces a *different* id, nothing deduplicates
  it against the first, and the account is swept twice. It is a one-word
  mistake with no symptom until the day somebody edits a number."
  ^UUID [policy-name event]
  (let [event-id (:event/id event)]
    (when-not (uuid? event-id)
      (throw (ex-info "Invalid event id" {:reason :invalid-event-id :event/id event-id})))
    (UUID/nameUUIDFromBytes (.getBytes (str policy-name "/" event-id) "UTF-8"))))

(def ^:private sweep "sweep-after-withdrawal")
(def ^:private review "review-large-withdrawals")

(defn react
  "Whenever money leaves the current account, top it up from savings; and
  whenever a withdrawal is large, ask for it to be reviewed.

  Both thresholds are configuration, and neither reaches the past."
  [config {:keys [event/type data] :as event}]
  (case type
    :money-withdrawn
    (cond-> [{:command/id   (derived-command-id sweep event)
              :command/type :withdraw
              :data         {:account         :savings
                             :amount          (:sweep-amount config)
                             :withdrawal-fee  (:withdrawal-fee config)
                             :overdraft-limit (:overdraft-limit config)}}]

      (pos? (compare (:amount data) (:review-above config)))
      (conj {:command/id   (derived-command-id review event)
             :command/type :flag-for-review
             :data         {:amount (:amount data)}}))

    ;; Known and deliberately uninteresting. Lab 10's rule: an explicit empty
    ;; reaction, so a reader can tell "nothing to do" from "not considered".
    :account-opened  []
    :money-deposited []

    (throw (ex-info "Unknown event type" {:reason :unknown-event-type
                                          :event/type type}))))

(defn react-to-all
  [config events]
  (into [] (mapcat (partial react config)) events))
