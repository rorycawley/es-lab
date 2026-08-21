(ns lab19.truck
  "The domain: what an Ice Cream truck knows.

  The decision model introduced in lab 8, still not handed a repository to
  talk to. It was written against values and now runs with Postgres outside
  it, because it was never told the difference.

  Technology independence is the claim this file demonstrates.")

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
  ;; A supported notification fact that does not change the stock fold.
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
;; recorded. The application identifies them; persistence assigns only storage
;; coordinates and transaction time.
;; ---------------------------------------------------------------------------

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :load-truck
  [command _state]
  (let [{:keys [quantity]} (:data command)]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    [{:event/type :truck-loaded :data (:data command)}]))

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
