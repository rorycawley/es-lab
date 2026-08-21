(ns lab15.domain
  "Two small aggregates, neither of which is this lab's subject.

  What matters is where the personal data is, and where it isn't:

    loyalty card stream   the customer's name and email — sealed
    truck stream          sales, carrying a customer id and nothing more

  That split is the *first* answer to erasure, and the better one: keep
  personal data out of the events that don't need it. Thousands of sales
  reference a subject without describing them, so erasing a customer never
  has to touch a single sale."
  (:require [lab15.vault :as vault]))

;; ---------------------------------------------------------------------------
;; The loyalty card. The one place a person is described.
;; ---------------------------------------------------------------------------

(def initial-card {:status :none})

(defmulti evolve-card (fn [_state event] (:event/type event)))

(defmethod evolve-card :card-issued
  [_state event]
  {:status      :active
   :customer-id (get-in event [:data :customer-id])
   :personal    (get-in event [:data :personal])})

(defmethod evolve-card :card-cancelled
  [state _event]
  (assoc state :status :cancelled))

(defmethod evolve-card :default
  [state _event]
  state)

(defn replay-card
  [events]
  (reduce evolve-card initial-card events))

(defmulti decide-card (fn [command _state] (:command/type command)))

(defmethod decide-card :issue-card
  [command _state]
  (let [{:keys [customer-id key personal]} (:data command)]
    ;; The name and email are part of this fact — there is no version of
    ;; "a card was issued to someone" that omits who. So they are sealed
    ;; rather than separated, which is the second answer to erasure and the
    ;; one you reach for when the first will not stretch.
    [{:event/type :card-issued
      :data       {:customer-id customer-id
                   :personal    (vault/seal key personal)}}]))

(defmethod decide-card :cancel-card
  [_command _state]
  [{:event/type :card-cancelled :data {}}])

;; ---------------------------------------------------------------------------
;; The truck. Sales name a customer and describe nobody.
;; ---------------------------------------------------------------------------

(def initial-truck {})

(defmulti evolve-truck (fn [_state event] (:event/type event)))

(defmethod evolve-truck :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve-truck :flavour-sold
  [state event]
  (update state (get-in event [:data :flavour]) (fnil dec 0)))

(defmethod evolve-truck :default
  [state _event]
  state)

(defn replay-truck
  [events]
  (reduce evolve-truck initial-truck events))

(defmulti decide-truck (fn [command _state] (:command/type command)))

(defmethod decide-truck :load-truck
  [command _state]
  (let [{:keys [flavour quantity]} (:data command)]
    [{:event/type :truck-loaded :data {:flavour flavour :quantity quantity}}]))

(defmethod decide-truck :buy-flavour
  [command state]
  (let [{:keys [flavour customer-id]} (:data command)
        remaining (get state flavour 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out" {:flavour flavour})))
    ;; A customer id, and nothing that describes the customer.
    [{:event/type :flavour-sold
      :data       {:flavour flavour :customer-id customer-id}}]))
