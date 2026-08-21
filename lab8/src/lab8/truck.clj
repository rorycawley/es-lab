(ns lab8.truck
  "The domain: what an Ice Cream truck knows.

  Nothing in this namespace knows there is a log, a store, or a stream. It
  takes values and returns values. `evolve` keeps lab 6's shape; `decide` is
  new.")

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
;; This is where context-dependent business rules may refuse a valid command,
;; because it runs while the business answer is still open. Values returned
;; here are event proposals: they carry no identity, stream, or version. The
;; application boundary identifies them before the store records them.
;; ---------------------------------------------------------------------------

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :load-truck
  [command _state]
  (let [{:keys [quantity]} (:data command)]
    ;; Loading nothing onto the truck is not a fact. Nothing happened, and
    ;; nothing went wrong either.
    (if (pos? quantity)
      [{:event/type :truck-loaded :data (:data command)}]
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
    ;; Selling the last cone is two facts, in the order they became true.
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))
