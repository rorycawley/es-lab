(ns lab10.truck
  "The technology-independent decision model introduced in lab 8.

  A policy issues commands, and the domain cannot tell where a command came
  from.")

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state          (lab 6)
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

(defmethod evolve :truck-repainted
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
;; decide : command -> state -> [event]      (lab 8)
;; ---------------------------------------------------------------------------

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
      (throw (ex-info "Sold out"
                      {:command/type :buy-flavour
                       :flavour      flavour
                       :remaining    remaining})))
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))
