(ns lab11.process
  "A process manager: a policy that remembers where it has got to.

  The process is a stock transfer. A truck runs out of a flavour; a donor
  truck is asked to give some up; the empty truck is then loaded with it. Two
  steps, two aggregates, and a wait in the middle that may never end.

  Note the shape. `evolve` folds observed events into the process's own state,
  and `decide` turns that state into the next request. That is lab 8's decider
  with one substitution: it emits commands rather than events."
  (:import (java.time Duration Instant)
           (java.util UUID)))

(def timeout
  "How long the process waits for the donor before giving up."
  (Duration/ofMinutes 30))

(def transfer-quantity 10)

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state
;;
;; The process has no stream of its own. Its history is every event sharing
;; its correlation id, which is what lets the fold span two trucks.
;; ---------------------------------------------------------------------------

(def initial-state {:status :not-started})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :stock-depleted
  [_state event]
  {:status     :awaiting-unload
   :flavour    (get-in event [:data :flavour])
   :to         (:stream/id event)
   :started-at (:event/occurred-at event)})

(defmethod evolve :flavour-unloaded
  [state _event]
  (assoc state :status :awaiting-load))

(defmethod evolve :truck-loaded
  [state _event]
  (assoc state :status :complete))

(defmethod evolve :transfer-abandoned
  [state _event]
  (assoc state :status :abandoned))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; Command identity, derived — as in lab 10, and for the same reason.
;;
;; The step is part of the derivation: one process issues several commands,
;; and each must be stable across redelivery without colliding with the next.
;; ---------------------------------------------------------------------------

(defn derived-command-id
  ^UUID [correlation-id step]
  (UUID/nameUUIDFromBytes (.getBytes (str "transfer/" correlation-id "/" (name step))
                                     "UTF-8")))

(defn- command
  [correlation-id step type data]
  {:command/id     (derived-command-id correlation-id step)
   :command/type   type
   :correlation-id correlation-id
   :data           data})

(defn- waited-too-long?
  [state now]
  (and (:started-at state)
       (.isAfter ^Instant (.toInstant ^java.util.Date now)
                 (.plus ^Instant (.toInstant ^java.util.Date (:started-at state))
                        timeout))))

;; ---------------------------------------------------------------------------
;; decide : state -> now -> [command]
;;
;; The process manager only ever issues commands. It never writes events
;; directly — every fact in the log is still produced by an aggregate that
;; decided it. That falls out of "a process manager routes; it does not
;; decide" (lab 2): if it wrote its own facts, it would be deciding.
;; ---------------------------------------------------------------------------

(defn decide
  [state correlation-id donor now]
  (case (:status state)
    :awaiting-unload
    (if (waited-too-long? state now)
      [(command correlation-id :abandon :abandon-transfer
                {:truck-id (:to state)
                 :flavour  (:flavour state)
                 :reason   "donor-did-not-respond"})]
      [(command correlation-id :unload :unload-flavour
                {:truck-id donor
                 :flavour  (:flavour state)
                 :quantity transfer-quantity})])

    :awaiting-load
    [(command correlation-id :load :load-truck
              {:truck-id (:to state)
               :flavour  (:flavour state)
               :quantity transfer-quantity})]

    ;; :not-started, :complete, :abandoned — nothing to ask for.
    []))
