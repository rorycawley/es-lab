(ns lab12.truck
  "The domain, as of lab 8. It has no idea anyone is listening — which is the
  point of putting the translation to integration messages somewhere else.")

(def initial-state {})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update state (get-in event [:data :flavour]) (fnil dec 0)))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :load-truck
  [command _state]
  (let [{:keys [flavour quantity]} (:data command)]
    (if (pos? quantity)
      [{:event/type :truck-loaded :data {:flavour flavour :quantity quantity}}]
      [])))

(defmethod decide :buy-flavour
  [command state]
  (let [flavour   (get-in command [:data :flavour])
        remaining (get state flavour 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out" {:flavour flavour :remaining remaining})))
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))
