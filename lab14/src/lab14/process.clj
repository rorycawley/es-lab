(ns lab14.process
  "The transfer from lab 11, with the failure it did not handle.

  Step one succeeds: the donor gives ten cones up. Step two fails: the empty
  truck has no room. Ten cones are now nowhere, and no rollback exists — the
  unload happened, and facts do not un-happen.

  What exists instead is a **compensating action**: a further business action
  whose effect is opposite. Not an erasure; an entry on the other side."
  (:import (java.time Duration Instant)
           (java.util UUID)))

(def timeout (Duration/ofMinutes 30))
(def transfer-quantity 10)

;; ---------------------------------------------------------------------------
;; States
;;
;;   :awaiting-unload  ──unloaded──▶ :awaiting-load ──loaded──▶  :complete
;;          │                              │
;;       timeout                     load-refused
;;          ▼                              ▼
;;      :abandoned                   :compensating ──returned──▶ :compensated
;;                                         │
;;                                   return refused
;;                                         ▼
;;                                  :needs-attention
;;
;; :abandoned needs no compensation — nothing had happened yet. That is the
;; whole reason to fail early where you can.
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
  [state event]
  ;; Remember who gave it up. Compensation has to know where to put it back,
  ;; and the answer is in the history rather than in a config file.
  (assoc state
         :status :awaiting-load
         :donor (:stream/id event)
         :quantity (get-in event [:data :quantity])))

(defmethod evolve :truck-loaded
  [state _event]
  (assoc state :status :complete))

(defmethod evolve :load-refused
  [state event]
  (assoc state :status :compensating :reason (get-in event [:data :reason])))

(defmethod evolve :flavour-returned
  [state _event]
  (assoc state :status :compensated))

(defmethod evolve :compensation-failed
  [state _event]
  (assoc state :status :needs-attention))

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
;; Commands
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

(defn decide
  [state correlation-id donor now]
  (case (:status state)
    :awaiting-unload
    (if (waited-too-long? state now)
      [(command correlation-id :abandon :abandon-transfer
                {:truck-id (:to state) :flavour (:flavour state)
                 :reason   "donor-did-not-respond"})]
      [(command correlation-id :unload :unload-flavour
                {:truck-id donor :flavour (:flavour state)
                 :quantity transfer-quantity})])

    :awaiting-load
    [(command correlation-id :load :load-truck
              {:truck-id (:to state) :flavour (:flavour state)
               :quantity (:quantity state)})]

    ;; The compensating step. Note it is a *different command* from a
    ;; delivery, addressed back to the donor the history named.
    :compensating
    [(command correlation-id :return :return-stock
              {:truck-id (:donor state) :flavour (:flavour state)
               :quantity (:quantity state)})]

    ;; :complete, :compensated, :abandoned, :needs-attention — terminal.
    []))
