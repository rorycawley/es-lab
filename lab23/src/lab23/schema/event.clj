(ns lab23.schema.event
  "Event schemas — **open**, because a stream outlives its readers.

  [Lab 13](../lab13) argued for tolerant representation changes within a known
  event type and version. Here that argument becomes a setting: these maps are
  *not* `:closed`, so a known event carrying a compatible field added after
  this code was deployed still reads. Unknown event semantics remain an error
  in the fold, policy and contract.

  Close these and you have re-created the failure lab 13 spends its length
  warning about — a reader that crashes on its own history.

  They also do a second job the command schemas do not: **decoding**. Not
  every loss at that boundary is avoidable — JSON has no UUID type — so a
  value the store hands back may still need restoring. See the note below on
  which losses are worth a decoder and which are worth not having."
  (:require [malli.core :as m]
            [malli.transform :as mt]))

(def Flavour [:enum "vanilla" "chocolate" "strawberry" "pistachio"])

;; Note the absence of {:closed true} throughout.
(def FlavourSold
  [:map
   [:flavour Flavour]])

(def TruckLoaded
  [:map
   [:flavour Flavour]
   [:quantity :int]])

(def StockDepleted
  [:map
   [:flavour Flavour]])

(def by-type
  {:flavour-sold   FlavourSold
   :truck-loaded   TruckLoaded
   :stock-depleted StockDepleted})

(def Metadata
  [:map
   [:causation-id {:optional true} :uuid]
   [:correlation-id {:optional true} :uuid]])

;; ---------------------------------------------------------------------------
;; Decoding: what lab 19 did by hand
;;
;;     (def keyword-valued #{:flavour :reason :reason-code})
;;
;; That is a list somebody must remember to extend. This is derived from the
;; schema, which you wanted anyway for validation and for documenting the
;; contract — so the coercion stops being a third thing that can drift.
;; ---------------------------------------------------------------------------

(def ^:private json->domain (mt/json-transformer))

;; ---------------------------------------------------------------------------
;; Decoding, and the losses worth decoding for
;;
;; Lab 19 kept a hand-maintained set of field names whose keyword values JSON
;; had flattened into strings:
;;
;;     (def keyword-valued #{:flavour :reason :reason-code})
;;
;; The set is gone, and so is the problem: nothing in a stream is written as a
;; keyword any more. You do not need a decoder for a loss you can decline to
;; have.
;;
;; What is left is the loss JSON hands you whether you like it or not. It has
;; no UUID type, so the causation and correlation ids in the event envelope go
;; in as UUIDs and come back as strings. No design decision can make JSON hold
;; a UUID; the adapter restores those declared envelope fields.
;;
;; Which is the distinction worth keeping. A schema-driven decoder is the
;; right tool for an **inherent** loss and the wrong one for a
;; **self-inflicted** loss, where it works perfectly and quietly props up a
;; decision you should have taken differently. Same code; only one of the two
;; uses is a good idea.
;; ---------------------------------------------------------------------------

(defn decode-data
  "Coerce one event's `:data` out of its wire representation.

  This generic storage adapter passes an unregistered type through because it
  has no semantic authority to decode it. A downstream fold, policy, contract
  or projection must still understand or explicitly ignore that event before
  advancing a checkpoint."
  [event-type data]
  (if-let [schema (get by-type event-type)]
    (m/decode schema data json->domain)
    data))

(defn decode-metadata
  "Restore the UUID fields declared in the event metadata envelope."
  [metadata]
  (m/decode Metadata metadata json->domain))

(defn valid-data?
  [event-type data]
  (if-let [schema (get by-type event-type)]
    (m/validate schema data)
    ;; No registered schema means this generic adapter cannot validate the
    ;; payload. It does not mean a semantic consumer understands the event.
    true))
