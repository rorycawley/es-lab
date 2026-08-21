(ns lab9.projection
  "Read models built by folding the whole log.

  A projection is the same operation as lab 6's `evolve` — reduce a function
  over events — pointed at a different question. `evolve` answers something
  about one truck, to decide with. A projection answers something about the
  business, to look at."
  (:require [lab9.store :as store]))

;; ---------------------------------------------------------------------------
;; Projection 1: which flavours sell.
;;
;; A question no single truck can answer, because the answer is about the
;; fleet. It ignores loading entirely — restocking is not a sale.
;; ---------------------------------------------------------------------------

(defmulti popularity (fn [_model event] (:event/type event)))

(defmethod popularity :flavour-sold
  [model event]
  (update model (get-in event [:data :flavour]) (fnil inc 0)))

(defmethod popularity :truck-loaded
  [model _event]
  model)

(defmethod popularity :stock-depleted
  [model _event]
  model)

(defmethod popularity :default
  [_model event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

;; ---------------------------------------------------------------------------
;; Projection 2: stock across the fleet, keyed by truck.
;;
;; Spans every stream, and still reports per truck. Note that it re-derives
;; something lab 8's aggregate also computes — that duplication is the point
;; of CQRS, not a mistake: one is for deciding, this one is for looking at.
;; ---------------------------------------------------------------------------

(defmulti fleet-stock (fn [_model event] (:event/type event)))

(defmethod fleet-stock :truck-loaded
  [model event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in model [(:stream/id event) flavour] (fnil + 0) quantity)))

(defmethod fleet-stock :flavour-sold
  [model event]
  (update-in model [(:stream/id event) (get-in event [:data :flavour])]
             (fnil dec 0)))

(defmethod fleet-stock :stock-depleted
  [model _event]
  model)

(defmethod fleet-stock :default
  [_model event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

;; ---------------------------------------------------------------------------
;; A read model is its data plus the position it has consumed up to.
;;
;; The checkpoint is what makes the fold resumable across restarts. Without
;; it, the only safe thing to do on startup is replay from the beginning.
;; ---------------------------------------------------------------------------

(defn empty-model
  "A persistable read-model value. The projection function is runtime wiring,
  not stored data."
  [initial-state]
  {:state      initial-state
   :checkpoint 0})

(defn advance
  "Fold everything appended since the checkpoint, then move the checkpoint to
  the greatest position actually consumed.

  Called repeatedly with no new events, this changes nothing — which is what
  lets a projection poll."
  [model project log]
  (let [new-events      (store/since log (:checkpoint model))
        next-checkpoint (reduce max (:checkpoint model)
                                (map :event/position new-events))]
    (-> model
        (update :state #(reduce project % new-events))
        (assoc :checkpoint next-checkpoint))))

(defn rebuild
  "Throw the read model away and fold the log from the beginning.

  Always available, because the read model holds nothing the events don't."
  ([project log]
   (rebuild project {} log))
  ([project initial-state log]
   (advance (empty-model initial-state) project log)))
