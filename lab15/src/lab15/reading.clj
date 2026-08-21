(ns lab15.reading
  "Unsealing on read — the same edge as lab 13's upcaster, for the same reason.

  Everything downstream sees plain values or an explicit marker, and never
  learns that encryption exists. A domain that had to decrypt would have the
  vault threaded through every fold forever."
  (:require [lab15.vault :as vault]))

(def erased
  "What a sealed field reads as once its key is gone.

  Not nil, and not a blank string. Both of those are values a field could
  legitimately have had, so both would let an erasure pass for data. Lab 13
  made the same choice with `:price/unknown`, and for the same reason: when
  something cannot be recovered, say so loudly enough that every reader has
  to decide what to do about it."
  :personal/erased)

(defn erased? [x] (= erased x))

(defn read-event
  "Return `event` with its sealed field opened, or marked erased.

  The stored event is untouched. Erasure destroys a key; it never rewrites
  history, which is what lets the log stay append-only."
  [vault event]
  (if-let [sealed (get-in event [:data :personal])]
    (let [subject (get-in event [:data :customer-id])
          key     (vault/key-for vault subject)]
      (assoc-in event [:data :personal]
                (if key (vault/unseal key sealed) erased)))
    event))

(defn read-all
  [vault events]
  (mapv #(read-event vault %) events))
