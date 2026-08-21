(ns lab14.truck
  "The domain, with one thing added: capacity.

  A truck can now refuse to be loaded, which is what gives the transfer in
  this lab a step that fails *after* an earlier step has already succeeded.")

(def initial-state
  {:capacity 0
   :stock    {}})

(defn total-stock
  [state]
  (reduce + 0 (vals (:stock state))))

(defn room-for
  [state quantity]
  (<= (+ (total-stock state) quantity) (:capacity state)))

;; ---------------------------------------------------------------------------
;; evolve
;; ---------------------------------------------------------------------------

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-commissioned
  [state event]
  (assoc state :capacity (get-in event [:data :capacity])))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-returned
  [state event]
  ;; A return puts stock back. It is a separate event type from a delivery on
  ;; purpose — the log must be able to show that this movement was an undo.
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-unloaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil - 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update-in state [:stock (get-in event [:data :flavour])] (fnil dec 0)))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; decide
;; ---------------------------------------------------------------------------

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :commission-truck
  [command _state]
  [{:event/type :truck-commissioned
    :data       {:capacity (get-in command [:data :capacity])}}])

(defmethod decide :buy-flavour
  [command state]
  (let [flavour   (get-in command [:data :flavour])
        remaining (get-in state [:stock flavour] 0)]
    (when-not (pos? remaining)
      (throw (ex-info "Sold out" {:flavour flavour})))
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))

(defmethod decide :unload-flavour
  [command state]
  (let [{:keys [flavour quantity]} (:data command)
        remaining (get-in state [:stock flavour] 0)]
    (when (< remaining quantity)
      (throw (ex-info "Not enough to spare" {:flavour flavour :remaining remaining})))
    [{:event/type :flavour-unloaded :data {:flavour flavour :quantity quantity}}]))

;; ---------------------------------------------------------------------------
;; The refusal that becomes a fact.
;;
;; Everywhere else in these labs a refusal throws and records nothing (lab 5).
;; Here it does not, and the reason is specific: a process manager is waiting
;; on the outcome, and it cannot observe silence. Lab 5's own exception clause
;; applies — if somebody needs to know, model it deliberately.
;;
;; The cost is real and permanent: :load-refused is in the log forever, and
;; every fold that does not care about it must now say so.
;; ---------------------------------------------------------------------------

(defmethod decide :load-truck
  [command state]
  (let [{:keys [flavour quantity]} (:data command)]
    (cond
      (not (pos? quantity)) []

      (not (room-for state quantity))
      [{:event/type :load-refused
        :data       {:flavour  flavour
                     :quantity quantity
                     :reason   "no-room"
                     :capacity (:capacity state)
                     :held     (total-stock state)}}]

      :else
      [{:event/type :truck-loaded :data {:flavour flavour :quantity quantity}}])))

(defmethod decide :return-stock
  [command state]
  (let [{:keys [flavour quantity]} (:data command)]
    ;; Compensation is an ordinary business action, so it can be refused like
    ;; any other. A donor that filled up while the transfer was in flight has
    ;; no room to take its own stock back.
    ;;
    ;; This refusal is a fact for the same reason :load-refused is: a process
    ;; is waiting on it, and there is nowhere else for it to find out.
    (if (room-for state quantity)
      [{:event/type :flavour-returned :data {:flavour flavour :quantity quantity}}]
      [{:event/type :compensation-failed
        :data       {:flavour  flavour
                     :quantity quantity
                     :reason   "no-room-to-return"
                     :capacity (:capacity state)
                     :held     (total-stock state)}}])))
