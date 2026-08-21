(ns lab18.truck
  "The domain, and the **rules version** it decided under.

  Labs 13 and 17 versioned two things: the shape of an event, and the shape of
  a fold. This adds the third — the shape of a *decision* — for the same
  reason. Explaining a decision made in 2019 requires the rules as they stood
  in 2019, and rules change more often than either of the others.")

(def initial-state {:stock {}})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update-in state [:stock (get-in event [:data :flavour])] (fnil dec 0)))

(defmethod evolve :sale-corrected
  [state event]
  ;; A correction is a new fact about an old moment: the till rang up the
  ;; wrong flavour. Put one cone back and take the other one off.
  (let [{:keys [from to]} (:data event)]
    (-> state
        (update-in [:stock from] (fnil inc 0))
        (update-in [:stock to] (fnil dec 0)))))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; Decision rules, versioned.
;;
;; `decide` is pure (lab 8), so replaying a command against the state it saw
;; reproduces its outcome exactly — but only against the rules it ran under.
;; Keep the old ones and that reconstruction is evidence; drop them and it is
;; a guess dressed as evidence.
;; ---------------------------------------------------------------------------

(def rules-version 2)

(defmulti decide
  (fn [command _state rules] [(:command/type command) rules]))

;; v1: any sale is fine as long as there is stock.
(defmethod decide [:buy-flavour 1]
  [command state _rules]
  (let [flavour (get-in command [:data :flavour])]
    (when-not (pos? (get-in state [:stock flavour] 0))
      (throw (ex-info "Sold out" {:flavour flavour})))
    [{:event/type :flavour-sold :data {:flavour flavour}}]))

;; v2: the truck now holds back two cones of each flavour for pre-orders.
(def reserve 2)

(defmethod decide [:buy-flavour 2]
  [command state _rules]
  (let [flavour (get-in command [:data :flavour])]
    (when-not (> (get-in state [:stock flavour] 0) reserve)
      (throw (ex-info "Reserved stock only" {:flavour flavour})))
    [{:event/type :flavour-sold :data {:flavour flavour}}]))

(defmethod decide [:load-truck 1]
  [command _state _rules]
  [{:event/type :truck-loaded :data (:data command)}])

(defmethod decide [:load-truck 2]
  [command state _rules]
  ;; Unchanged between versions. Most rules are.
  (decide command state 1))
