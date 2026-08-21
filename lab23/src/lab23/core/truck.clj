(ns lab23.core.truck
  "The domain: what an Ice Cream truck knows.

  This retains the technology-independent decision model introduced in lab 8.
  Worth noticing: nothing about an outbox, an inbox or a ledger reaches this
  file either.")

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state          (lab 6, trimmed to stock)
;; ---------------------------------------------------------------------------

(def initial-state {})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update state (get-in event [:data :flavour]) (fnil dec 0)))

(defmethod evolve :stock-depleted
  [state _event]
  state)

(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; decide : command -> state -> [event]
;;
;; The only function in these labs that is allowed to say no, because it is
;; the only one that runs while the answer is still open. Events returned here
;; carry no identity and no stream — they are what happened, not where it is
;; recorded. The application identifies them; persistence adds coordinates.
;; ---------------------------------------------------------------------------

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :load-truck
  [command _state]
  (let [{:keys [flavour quantity]} (:data command)]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity :quantity quantity})))
    [{:event/type :truck-loaded
      :data {:flavour flavour :quantity quantity}}]))

(defmethod decide :ensure-stock
  [command state]
  (let [{:keys [flavour quantity]} (:data command)
        current (get state flavour 0)]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity :quantity quantity})))
    (if (>= current quantity)
      []
      [{:event/type :truck-loaded
        :data {:flavour flavour :quantity (- quantity current)}}])))

(defmethod decide :buy-flavour
  [command state]
  (let [flavour   (get-in command [:data :flavour])
        remaining (get state flavour 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out"
                      {:reason       :sold-out
                       :command/type :buy-flavour
                       :flavour      flavour
                       :remaining    remaining})))
    ;; Selling the last cone is two facts, in the order they became true.
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))

(defmethod decide :default
  [command _state]
  (throw (ex-info "Unknown command type"
                  {:command/type (:command/type command)})))
