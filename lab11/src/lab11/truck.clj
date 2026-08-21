(ns lab11.truck
  "The domain. Two new commands beyond lab 8, both needed by the transfer the
  process manager coordinates: give some stock up, and record that a transfer
  was given up on.")

(def initial-state {})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update state (get-in event [:data :flavour]) (fnil dec 0)))

(defmethod evolve :flavour-unloaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil - 0) quantity)))

(defmethod evolve :stock-depleted
  [state _event]
  state)

(defmethod evolve :transfer-abandoned
  [state _event]
  state)

(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

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
      (throw (ex-info "Sold out"
                      {:reason :sold-out
                       :command/type :buy-flavour
                       :flavour flavour
                       :remaining remaining})))
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))

(defmethod decide :unload-flavour
  [command state]
  (let [{:keys [flavour quantity]} (:data command)
        remaining (get state flavour 0)]
    ;; The donor refuses if it cannot spare the stock. Note what a refusal
    ;; produces: nothing. No event, no trace — which is exactly why the thing
    ;; waiting on this needs a timeout rather than a reply.
    (when (< remaining quantity)
      (throw (ex-info "Not enough to spare"
                      {:reason :not-enough-to-spare
                       :command/type :unload-flavour
                       :flavour flavour
                       :remaining remaining
                       :asked quantity})))
    [{:event/type :flavour-unloaded :data {:flavour flavour :quantity quantity}}]))

(defmethod decide :abandon-transfer
  [command _state]
  [{:event/type :transfer-abandoned
    :data       {:flavour (get-in command [:data :flavour])
                 :reason  (get-in command [:data :reason])}}])

(defmethod decide :default
  [command _state]
  (throw (ex-info "Unknown command type"
                  {:command/type (:command/type command)})))
