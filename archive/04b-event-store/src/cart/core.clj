(ns cart.core
  "Pure functional core. No requires, by design (SPEC R1.1).

   Keywords are written as fully-qualified literals rather than with `::`,
   because `::` needs an alias and an alias needs a require. A keyword is a
   value; it costs no dependency.")

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def initial-state
  "A map, not a function: Clojure maps are immutable, so there is nothing to
   defend against by handing out a fresh one each time."
  {:status :empty})

(defn- total-items [state]
  (reduce + 0 (vals (:product-items state))))

(defn- adjust-quantity
  "Add delta to product-id's quantity, dropping the key when it reaches zero so
   the state schema's pos-int? constraint holds."
  [product-items product-id delta]
  (let [updated (update product-items product-id (fnil + 0) delta)]
    (if (pos? (get updated product-id))
      updated
      (dissoc updated product-id))))

;; ---------------------------------------------------------------------------
;; evolve — events to state
;; ---------------------------------------------------------------------------

(defmulti evolve
  "(evolve state event) -> state. State first, so it drops into reduce."
  (fn [_state event] (:type event)))

;; SPEC R2.6. Load-bearing: an event type written by a newer deployment and
;; read back after a rollback must not make the aggregate unloadable.
(defmethod evolve :default [state _event] state)

(defmethod evolve :cart.event/product-item-added
  [state event]
  (let [{:keys [product-id quantity]} (get-in event [:data :product-item])]
    (if (= :closed (:status state))
      state
      {:status        :opened
       :product-items (adjust-quantity (:product-items state {}) product-id quantity)})))

(defmethod evolve :cart.event/product-item-removed
  [state event]
  (let [{:keys [product-id quantity]} (get-in event [:data :product-item])]
    (if (= :closed (:status state))
      state
      {:status        :opened
       :product-items (adjust-quantity (:product-items state {}) product-id (- quantity))})))

;; A closed cart drops :product-items entirely. No rule in decide consults them
;; once closed, so carrying them is dead weight that invites stale reasoning.
(defmethod evolve :cart.event/confirmed [_state _event] {:status :closed})
(defmethod evolve :cart.event/cancelled [_state _event] {:status :closed})

(defn fold
  "Rebuild state from events. Pure — the shell does the reading.

   The 2-arity is for folding from a snapshot later."
  ([events] (fold initial-state events))
  ([state events] (reduce evolve state events)))

;; ---------------------------------------------------------------------------
;; decide — command + state to events
;; ---------------------------------------------------------------------------

(defmulti decide
  "(decide command state) -> [:ok events] | [:error {:reason kw}]

   Command first, because the command is the subject. Deliberately has no
   :default method: an unknown command is a bug in current code (SPEC R2.6)."
  (fn [command _state] (:type command)))

(defmethod decide :cart.command/add-product-item
  [{{:keys [cart-id product-item]} :data {:keys [now]} :metadata} state]
  (if (= :closed (:status state))
    [:error {:reason :cart-closed}]
    [:ok [{:type :cart.event/product-item-added
           :data {:cart-id      cart-id
                  :product-item product-item
                  :added-at     now}}]]))

(defmethod decide :cart.command/remove-product-item
  [{{:keys [cart-id product-item]} :data {:keys [now]} :metadata} state]
  (let [{:keys [product-id quantity]} product-item
        held (get (:product-items state {}) product-id 0)]
    (cond
      (= :closed (:status state)) [:error {:reason :cart-closed}]
      (> quantity held)           [:error {:reason :insufficient-quantity}]
      :else
      [:ok [{:type :cart.event/product-item-removed
             :data {:cart-id      cart-id
                    :product-item product-item
                    :removed-at   now}}]])))

(defmethod decide :cart.command/confirm
  [{{:keys [cart-id]} :data {:keys [now]} :metadata} state]
  (cond
    (not= :opened (:status state)) [:error {:reason :not-opened}]
    (zero? (total-items state))    [:error {:reason :empty-cart}]
    :else
    [:ok [{:type :cart.event/confirmed
           :data {:cart-id cart-id :confirmed-at now}}]]))

(defmethod decide :cart.command/cancel
  [{{:keys [cart-id]} :data {:keys [now]} :metadata} state]
  (if (= :closed (:status state))
    [:error {:reason :already-closed}]
    [:ok [{:type :cart.event/cancelled
           :data {:cart-id cart-id :cancelled-at now}}]]))
