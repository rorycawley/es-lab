(ns lab24.core.policy
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
  (UUID/nameUUIDFromBytes (.getBytes (str policy-name "/" (:event/id event))
                                     "UTF-8")))

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
  ;; Note what this does NOT do: it does not check whether restocking is
  ;; allowed, whether the depot has stock, or whether the truck is off shift.
  ;; A policy routes; `decide` decides. Business logic here would put the
  ;; rules in two places and let them disagree.
  [{:command/id   (derived-command-id :restock-when-depleted event)
    :command/type :load-truck
    ;; ── Authority does not propagate ───────────────────────────────────
    ;;
    ;; The customer who bought the last cone did not ask for a restock and
    ;; did not authorise one. The *conversation* continues — lab 11's
    ;; correlation id runs the whole length of it, and the causation id
    ;; points back at the depletion — but authority does not travel with it.
    ;;
    ;; So the actor is stamped, not inherited. Copying the triggering user
    ;; onto a command they never issued writes a false record (lab 1: a
    ;; process manager is not a person), and hands every user the authority
    ;; of every rule their actions happen to trigger.
    ;;
    ;; Correlation answers *what was this part of*. The actor answers *who
    ;; is answerable for it*. Different questions, and only one of them is
    ;; inherited.
    ;;
    ;; Strings, both of them. Lab 1 asked for an *opaque* identifier, and a
    ;; keyword is a program symbol — the one thing an opaque id is not.
    :command/actor {:type "system" :id "restock-when-depleted"}
    :data         {:truck-id (:stream/id event)
                   :flavour  (get-in event [:data :flavour])
                   :quantity restock-quantity}}])

;; Every other event type. A policy has an opinion about a handful of facts
;; and shrugs at the rest, exactly as a fold does (lab 6).
(defmethod react :default
  [_event]
  [])

(defn react-to-all
  "The commands asked for by a batch of events, in order."
  [events]
  (into [] (mapcat react) events))
