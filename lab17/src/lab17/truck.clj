(ns lab17.truck
  "The domain — and, more to the point, **the fold**.

  A snapshot is a cached result of `evolve`. So the thing a snapshot is
  vulnerable to is not a change to the events; it is a change to *this
  namespace*. `fold-version` is what makes that detectable.")

;; ---------------------------------------------------------------------------
;; Bump this whenever the SHAPE of the state changes — a new key, a renamed
;; one, a counter that starts counting something else.
;;
;; It has nothing to do with event schema versions (lab 13). Events and folds
;; change on different schedules for different reasons, and a snapshot is at
;; the mercy of the second.
;; ---------------------------------------------------------------------------

(def fold-version 2)

(def initial-state
  {:stock {}    ; flavour -> cones on board
   :sold  0})   ; cones sold, ever

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (-> state
      (update-in [:stock (get-in event [:data :flavour])] (fnil dec 0))
      (update :sold inc)))

(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn replay
  "Fold from the beginning. Always correct, and always the most work."
  [events]
  (reduce evolve initial-state events))

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :load-truck
  [command _state]
  (let [quantity (get-in command [:data :quantity])]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    [{:event/type :truck-loaded :data (:data command)}]))

(defmethod decide :buy-flavour
  [command state]
  (let [flavour (get-in command [:data :flavour])]
    (when-not (pos? (get-in state [:stock flavour] 0))
      (throw (ex-info "Sold out"
                      {:reason :sold-out
                       :flavour flavour})))
    [{:event/type :flavour-sold :data (:data command)}]))

(defmethod decide :default
  [command _state]
  (throw (ex-info "Unknown command type"
                  {:command/type (:command/type command)})))
