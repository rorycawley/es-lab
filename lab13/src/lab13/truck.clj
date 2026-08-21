(ns lab13.truck
  "The domain. Read it looking for version numbers: there are none.

  Every event reaching `evolve` has already been walked to the current shape
  by `upcast/read-event`, so this namespace only ever knew one `:flavour-sold`.
  That is what upcasting at the edge buys — the alternative is a `case` on
  schema version inside the fold, in every method, forever."
  (:require [lab13.upcast :as upcast]))

(def initial-state
  {:sold      {}     ; cones sold, by flavour
   :net       0M     ; revenue excluding VAT
   :incomplete 0})   ; sales whose price was never recorded

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :flavour-sold
  [state event]
  (let [{:keys [flavour unit-price]} (:data event)]
    (cond-> (update-in state [:sold flavour] (fnil inc 0))
      ;; The unknown propagates. A total that quietly treated a missing price
      ;; as zero would be wrong and would look right; a count of what is
      ;; missing lets a reader judge whether the total can be relied on.
      (upcast/unknown-price? unit-price) (update :incomplete inc)
      (not (upcast/unknown-price? unit-price)) (update :net + unit-price))))

(defmethod evolve :flavour-sold-gross
  [state event]
  ;; A different fact, folded differently. The price includes VAT, so it is
  ;; converted to reach the same quantity the other branch accumulates.
  (let [{:keys [flavour unit-price]} (:data event)]
    (-> state
        (update-in [:sold flavour] (fnil inc 0))
        (update :net + (/ unit-price (+ 1M upcast/vat-rate))))))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  "Fold stored events. Reading is the only place versions exist."
  [stored-events]
  (reduce evolve initial-state (upcast/read-all stored-events)))
