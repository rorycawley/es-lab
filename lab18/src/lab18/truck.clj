(ns lab18.truck
  "The domain, and the **rules version** it decided under.

  Labs 13 and 17 versioned two things: the shape of an event, and the shape of
  a fold. This adds the third — the shape of a *decision* — for the same
  reason. Explaining a decision made in 2019 requires the rules as they stood
  in 2019, and rules change more often than either of the others.")

(def initial-state
  {:stock {}
   :sales {}})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (let [flavour (get-in event [:data :flavour])]
    (-> state
        (update-in [:stock flavour] (fnil dec 0))
        (assoc-in [:sales (:event/id event)]
                  {:flavour     flavour
                   :occurred-at (:event/occurred-at event)
                   :corrected?  false}))))

(defmethod evolve :sale-corrected
  [state event]
  ;; The correction is recorded now but changes our current account of an
  ;; identified sale from its historical effective time.
  (let [{:keys [sale-id from to]} (:data event)]
    (-> state
        (update-in [:stock from] (fnil inc 0))
        (update-in [:stock to] (fnil dec 0))
        (assoc-in [:sales sale-id :corrected?] true))))

(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

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
      (throw (ex-info "Sold out"
                      {:reason :sold-out
                       :flavour flavour})))
    [{:event/type :flavour-sold :data {:flavour flavour}}]))

;; v2: the truck now holds back two cones of each flavour for pre-orders.
(def reserve 2)

(defmethod decide [:buy-flavour 2]
  [command state _rules]
  (let [flavour (get-in command [:data :flavour])]
    (when-not (> (get-in state [:stock flavour] 0) reserve)
      (throw (ex-info "Reserved stock only"
                      {:reason :reserved-stock-only
                       :flavour flavour
                       :reserve reserve})))
    [{:event/type :flavour-sold :data {:flavour flavour}}]))

(defmethod decide [:load-truck 1]
  [command _state _rules]
  (let [quantity (get-in command [:data :quantity])]
    (when-not (and (int? quantity) (pos? quantity))
      (throw (ex-info "Quantity must be a positive integer"
                      {:reason :invalid-quantity
                       :quantity quantity})))
    [{:event/type :truck-loaded :data (:data command)}]))

(defmethod decide [:load-truck 2]
  [command state _rules]
  ;; Unchanged between versions. Most rules are.
  (decide command state 1))

(defmethod decide [:correct-sale 1]
  [command state _rules]
  (let [{:keys [sale-id from to effective-at] :as data} (:data command)
        sale (get-in state [:sales sale-id])]
    (when-not (inst? effective-at)
      (throw (ex-info "Invalid correction effective-at instant"
                      {:reason :invalid-effective-at
                       :effective-at effective-at})))
    (when-not sale
      (throw (ex-info "Unknown sale"
                      {:reason :unknown-sale
                       :sale-id sale-id})))
    (when (:corrected? sale)
      (throw (ex-info "Sale already corrected"
                      {:reason :sale-already-corrected
                       :sale-id sale-id})))
    (when-not (= from (:flavour sale))
      (throw (ex-info "Correction does not match the sale"
                      {:reason :correction-mismatch
                       :sale-id sale-id
                       :expected (:flavour sale)
                       :from from})))
    (when-not (= effective-at (:occurred-at sale))
      (throw (ex-info "Correction effective time must match the sale"
                      {:reason :correction-effective-time-mismatch
                       :sale-id sale-id
                       :expected (:occurred-at sale)
                       :effective-at effective-at})))
    (when (= from to)
      (throw (ex-info "Correction must change the flavour"
                      {:reason :same-flavour-correction
                       :sale-id sale-id
                       :flavour from})))
    [{:event/type :sale-corrected :data data}]))

(defmethod decide [:correct-sale 2]
  [command state _rules]
  (decide command state 1))

(defmethod decide :default
  [command _state rules]
  (throw (ex-info "Unknown command or rules version"
                  {:command/type (:command/type command)
                   :rules-version rules})))
