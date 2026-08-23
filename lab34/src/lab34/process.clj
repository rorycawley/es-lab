(ns lab34.process
  "The process manager: `evolve` over observed events, `decide` from state and
  time. Lab 11's shape, with the state machine taken out of the code.

  Read the argument order of `evolve`:

      (evolve definition state event)

  The definition is a **parameter of the fold**, and lab 33 spent a whole lab
  saying a fold must not read configuration. This does not break that rule, it
  is that rule's consequence.

  The distinction is where the definition comes from. If this function reached
  into a registry for `the current definition`, then folding an instance's
  events tomorrow could land it in a different state than folding them today —
  or in a state that no longer exists. Same events, different answer, nothing
  recorded about why. That is precisely lab 33's forbidden case.

  So the definition is not configuration to an instance. It is a **decision
  input**, passed in, and the instance records which version it was given —
  exactly as lab 33's withdrawal records the overdraft limit that permitted
  it. `instance.clj` is where that pinning lives, and `pinning_test.clj` is
  where it is asserted.

  Time is an argument too, per lab 11: a process that asks the clock cannot be
  replayed to the same answer twice."
  (:require [lab34.definition :as definition])
  (:import (java.time Duration Instant)
           (java.util UUID)))

;; ---------------------------------------------------------------------------
;; evolve : definition -> state -> event -> state
;; ---------------------------------------------------------------------------

(defn initial-state
  [definition]
  {:status     (:initial definition)
   :entered-at nil
   :history    []})

(defn evolve
  "Advance the process by one observed fact.

  An event the current state has no transition for is **ignored, not
  refused**, and the distinction matters. A process manager observes a stream
  it does not own: most of what it sees is somebody else's business. Lab 11
  threw on an unknown event type because its transitions were code and a
  missing method meant a missing decision. Here the transitions are data and
  the definition is checked as a whole, so 'no transition from this state on
  this event' is an answer rather than a gap.

  What is *not* tolerated is an unknown state, which would mean the instance
  is folding under a definition that does not describe it — the migration
  failure this lab is about."
  [definition state {:keys [event/type occurred-at] :as event}]
  (when-not (contains? (definition/states definition) (:status state))
    (throw (ex-info "This definition does not describe the state the instance is in"
                    {:reason  :state-not-in-definition
                     :status  (:status state)
                     :version (definition/version-of definition)})))
  (if-let [target (definition/next-state definition (:status state) type)]
    (-> state
        (assoc :status target :entered-at occurred-at)
        (update :history conj {:from (:status state) :on type :to target
                               :at occurred-at :event/id (:event/id event)}))
    state))

(defn replay
  [definition events]
  (reduce (partial evolve definition) (initial-state definition) events))

;; ---------------------------------------------------------------------------
;; decide : definition -> state -> now -> [command]
;; ---------------------------------------------------------------------------

(defn- derived-command-id
  "Lab 10's derivation, and lab 33's rule about it.

  Derived from the instance, the state it is asking from, and why — never from
  the definition version or any configured value. A migrated instance must not
  re-issue a command it already issued, and it would if the version were in
  this hash.

  The `reason` is in it because one state can ask for two different things: a
  command on entry and another when it has waited too long. Those are two
  requests and need two identities, or the second deduplicates against the
  first and the escalation never happens. Both reasons are structural, so
  including them costs nothing in stability."
  ^UUID [process-id state reason]
  (UUID/nameUUIDFromBytes
   (.getBytes (str "lab34/" process-id "/" (name state) "/" (name reason)) "UTF-8")))

(defn- timed-out?
  [definition {:keys [status entered-at]} ^Instant now]
  (when-let [{:keys [^Duration after]} (definition/timeout-of definition status)]
    (and entered-at
         (not (.isBefore now (.plus ^Instant entered-at after))))))

(defn decide
  "What this instance asks for, given where it is and what time it is.

  Zero, one or two commands — lab 5's counting, one message further along. A
  state may ask for something on entry, and a state that has waited too long
  may ask for something else."
  [definition {:keys [status] :as state} process-id ^Instant now]
  (let [entry   (definition/issued-by definition status)
        overdue (when (timed-out? definition state now)
                  (:issue (definition/timeout-of definition status)))]
    (into []
          (for [[command reason] [[entry :entered] [overdue :timed-out]]
                :when command]
            {:command/id   (derived-command-id process-id status reason)
             :command/type command
             :data         {:process-id process-id
                            :state      status
                            :because    reason}}))))

(defn complete?
  [definition state]
  (definition/terminal? definition (:status state)))
