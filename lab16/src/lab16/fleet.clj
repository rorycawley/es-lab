(ns lab16.fleet
  "**Design A** — one aggregate for the whole fleet.

  Every truck and the depot live in one stream, so every invariant that spans
  them is enforceable in one `decide`. Including the one that matters:

      the depot cannot go negative

  Read this design looking for what it gets right. It is not a strawman: it is
  the only one of the three that can refuse an over-draw at the moment of the
  decision, and if that rule has legal force, this is the design you want.

  What it costs is measured in `contention.clj`.")

(def initial-state
  {:depot  {}    ; flavour -> cones at the depot
   :trucks {}})  ; truck-id -> {flavour -> cones}

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :depot-stocked
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:depot flavour] (fnil + 0) quantity)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [truck-id flavour quantity]} (:data event)]
    (-> state
        (update-in [:depot flavour] (fnil - 0) quantity)
        (update-in [:trucks truck-id flavour] (fnil + 0) quantity))))

(defmethod evolve :flavour-sold
  [state event]
  (let [{:keys [truck-id flavour]} (:data event)]
    (update-in state [:trucks truck-id flavour] (fnil dec 0))))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :stock-depot
  [command _state]
  [{:event/type :depot-stocked :data (:data command)}])

(defmethod decide :load-truck
  [command state]
  (let [{:keys [flavour quantity]} (:data command)
        at-depot (get-in state [:depot flavour] 0)]
    ;; The invariant, enforced immediately, because this aggregate can see
    ;; both sides of the movement.
    (when (< at-depot quantity)
      (throw (ex-info "Depot cannot cover that"
                      {:flavour flavour :at-depot at-depot :asked quantity})))
    [{:event/type :truck-loaded :data (:data command)}]))

(defmethod decide :buy-flavour
  [command state]
  (let [{:keys [truck-id flavour]} (:data command)
        remaining (get-in state [:trucks truck-id flavour] 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out" {:truck-id truck-id :flavour flavour})))
    [{:event/type :flavour-sold :data (:data command)}]))
