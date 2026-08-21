(ns lab22.core.truck
  "The domain: what an Ice Cream truck knows.

  Still lab 8's, unchanged — lab 19 made that argument and this lab inherits
  it. Worth noticing anyway: nothing about an outbox, an inbox or a ledger
  reaches this file either.")

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

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; decide : command -> state -> [event]
;;
;; The only function in these labs that is allowed to say no, because it is
;; the only one that runs while the answer is still open. Events returned here
;; carry no identity and no stream — they are what happened, not where it is
;; recorded. The store stamps the rest.
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
