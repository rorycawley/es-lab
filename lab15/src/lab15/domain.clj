(ns lab15.domain
  "Two small aggregates, neither of which is this lab's subject.

  What matters is where the personal data is, and where it isn't:

    loyalty card stream   the customer's name and email — sealed
    truck stream          sales, carrying a customer id and nothing more

  That split is the *first* answer to erasure, and the better one: keep
  personal data out of the events that don't need it. Thousands of sales
  reference a subject without repeating their direct identifiers, so a change
  to those attributes never has to rewrite every sale.")

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
  [_state event]
  (throw (ex-info "Unknown card event type"
                  {:event/type (:event/type event)})))

(defn replay-card
  [events]
  (reduce evolve-card initial-card events))

(defmulti decide-card (fn [command _state] (:command/type command)))

(defmethod decide-card :issue-card
  [command state]
  (when-not (= :none (:status state))
    (throw (ex-info "Card already issued"
                    {:reason :card-already-issued})))
  (let [{:keys [customer-id personal]} (:data command)]
    ;; The domain proposes plaintext business data. Protection is an edge
    ;; concern applied before append, so this pure decision knows nothing of
    ;; keys, algorithms or ciphertext.
    [{:event/type :card-issued
      :data       {:customer-id customer-id
                   :personal    personal}}]))

(defmethod decide-card :cancel-card
  [_command state]
  (when-not (= :active (:status state))
    (throw (ex-info "Card is not active"
                    {:reason :card-not-active})))
  [{:event/type :card-cancelled :data {}}])

(defmethod decide-card :default
  [command _state]
  (throw (ex-info "Unknown card command type"
                  {:command/type (:command/type command)})))

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
  [_state event]
  (throw (ex-info "Unknown truck event type"
                  {:event/type (:event/type event)})))

(defn replay-truck
  [events]
  (reduce evolve-truck initial-truck events))

(defmulti decide-truck (fn [command _state] (:command/type command)))

(defmethod decide-truck :load-truck
  [command _state]
  (let [{:keys [flavour quantity]} (:data command)]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    [{:event/type :truck-loaded :data {:flavour flavour :quantity quantity}}]))

(defmethod decide-truck :buy-flavour
  [command state]
  (let [{:keys [flavour customer-id]} (:data command)
        remaining (get state flavour 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out"
                      {:reason :sold-out
                       :flavour flavour
                       :remaining remaining})))
    ;; A customer id, and nothing that describes the customer.
    [{:event/type :flavour-sold
      :data       {:flavour flavour :customer-id customer-id}}]))

(defmethod decide-truck :default
  [command _state]
  (throw (ex-info "Unknown truck command type"
                  {:command/type (:command/type command)})))
