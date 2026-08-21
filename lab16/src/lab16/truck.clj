(ns lab16.truck
  "One truck, one stream. Used by **design B** and **design C**.

  Identical in both. What changes between them is not the truck — it is
  whether anything owns the depot's stock, which is the point: the boundary
  decision is about where an invariant lives, not about how an entity is
  written.")

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
  ;; Note what is *not* checked. A truck has no idea what the depot holds, and
  ;; under design B nothing else does either.
  [{:event/type :truck-loaded :data (:data command)}])

(defmethod decide :buy-flavour
  [command state]
  (let [flavour   (get-in command [:data :flavour])
        remaining (get state flavour 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out" {:flavour flavour})))
    [{:event/type :flavour-sold :data (:data command)}]))
