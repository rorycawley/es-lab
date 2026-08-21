(ns lab22.core.policy
  "A policy: the reactive rule that turns a fact into a request.

  Event Storming draws it as the sticky between an event and a command, and it
  always reads *whenever…* — whenever a truck runs out of a flavour, load more.

  Nothing here knows about a log, a store, or a broker. A policy is a function
  from one event to the commands it asks for."
  (:import (java.util UUID)))

;; ---------------------------------------------------------------------------
;; The command's identity is DERIVED, not minted.
;;
;; A reactor is fed by at-least-once delivery, so the same event can arrive
;; twice. A random id would make the second arrival look like a second
;; request. Deriving the id from the triggering event means the retry produces
;; the identical command, which is what makes it recognisable as a repeat.
;;
;; The policy's own name is part of the derivation: two policies reacting to
;; the same event must not collide on one command id.
;; ---------------------------------------------------------------------------

(defn derived-command-id
  ^UUID [policy-name event]
  (let [event-id (:event/id event)]
    (when-not (uuid? event-id)
      (throw (ex-info "Invalid event id"
                      {:event/id event-id})))
    (UUID/nameUUIDFromBytes (.getBytes (str policy-name "/" event-id)
                                       "UTF-8"))))

;; ---------------------------------------------------------------------------
;; react : event -> [command]
;;
;; Zero, one, or many — the same counting as lab 5, one message further along.
;; ---------------------------------------------------------------------------

(def restock-quantity 20)

(defmulti react :event/type)

(defmethod react :stock-depleted
  [event]
  ;; Whenever a truck runs out of a flavour, ask for more.
  ;;
  ;; This policy owns the reaction "depleted -> request a restock of 20". It
  ;; does not copy the target aggregate's state-dependent acceptance rules;
  ;; those remain authoritative in `decide`.
  [{:command/id   (derived-command-id :restock-when-depleted event)
    :command/type :load-truck
    :correlation-id (or (get-in event [:metadata :correlation-id])
                        (:event/id event))
    :data         {:truck-id (:stream/id event)
                   :flavour  (get-in event [:data :flavour])
                   :quantity restock-quantity}}])

(defmethod react :truck-loaded
  [_event]
  [])

(defmethod react :flavour-sold
  [_event]
  [])

;; Known irrelevant facts are explicit. Unknown semantics may require a new
;; reaction, so an old reader must not checkpoint past them silently.
(defmethod react :default
  [event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn react-to-all
  "The commands asked for by a batch of events, in order."
  [events]
  (into [] (mapcat react) events))
