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

(defmulti read-event
  "Return a known event in its domain-facing shape.

  Only absence of the subject key becomes `:personal/erased`. Authentication,
  format and semantic failures remain visible rather than masquerading as a
  successful erasure."
  (fn [_vault event] (:event/type event)))

(defmethod read-event :card-issued
  [key-vault event]
  (let [sealed  (get-in event [:data :personal])
        subject (get-in event [:data :customer-id])
        key     (vault/key-for key-vault subject)]
    (vault/validate-sealed sealed)
    (assoc-in event [:data :personal]
              (if key
                (vault/unseal key
                              (vault/personal-context subject (:event/id event))
                              sealed)
                erased))))

(defmethod read-event :card-cancelled
  [_vault event]
  event)

(defmethod read-event :truck-loaded
  [_vault event]
  event)

(defmethod read-event :flavour-sold
  [_vault event]
  event)

(defmethod read-event :default
  [_vault event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn read-all
  [vault events]
  (mapv #(read-event vault %) events))
