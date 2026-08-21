(ns lab16.depot
  "**Design C** — the depot owns its own stock, in its own stream.

  This is the whole of the difference from design B. The invariant did not
  move to a bigger aggregate; it moved to the *smallest* one that owns it.

  A truck cannot enforce 'the depot may not go negative' because a truck does
  not own the depot's stock. Nothing does, in design B. Here something does,
  and it refuses at the moment of the decision — the same immediacy design A
  bought by putting everything in one stream, without making unrelated truck
  sales contend on the depot stream.")

(def initial-state {})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :depot-stocked
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve :stock-issued
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil - 0) quantity)))

(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn replay
  [events]
  (reduce evolve initial-state events))

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :stock-depot
  [command _state]
  (let [quantity (get-in command [:data :quantity])]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    [{:event/type :depot-stocked :data (:data command)}]))

(defmethod decide :issue-stock
  [command state]
  (let [{:keys [flavour quantity]} (:data command)
        held (get state flavour 0)]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    (when (< held quantity)
      (throw (ex-info "Depot cannot cover that"
                      {:reason :insufficient-depot-stock
                       :flavour flavour :held held :asked quantity})))
    ;; Issuing is a fact about the depot. Putting it on the truck is a second
    ;; fact about the truck, in a second stream. A complete application would
    ;; join them with a process rather than this aggregate (lab 11), including
    ;; an explicit recovery decision if the second step fails (lab 14).
    [{:event/type :stock-issued :data (:data command)}]))

(defmethod decide :default
  [command _state]
  (throw (ex-info "Unknown command type"
                  {:command/type (:command/type command)})))
