(ns lab23.schema.event
  "Event schemas — **open**, because a stream outlives its readers.

  [Lab 13](../lab13) argued that a fold must tolerate event types it has never
  heard of, and that a deserialiser must handle every schema ever written.
  Here that argument becomes a setting: these maps are *not* `:closed`, so an
  event carrying a field added after this code was deployed still reads.

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
   [:quantity :int]
   ;; A policy stamps this (lab 10), and JSON has nowhere to put a UUID. The
   ;; one field in this lab that genuinely needs decoding on the way back.
   [:truck-id {:optional true} :uuid]])

(def StockDepleted
  [:map
   [:flavour Flavour]])

(def by-type
  {:flavour-sold   FlavourSold
   :truck-loaded   TruckLoaded
   :stock-depleted StockDepleted})

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
;; no UUID type, so `:truck-id` — put in the data by a policy (lab 10) — goes
;; in as a UUID and comes back as a string. No decision at design time avoids
;; that. JSON cannot hold one.
;;
;; Which is the distinction worth keeping. A schema-driven decoder is the
;; right tool for an **inherent** loss and the wrong one for a
;; **self-inflicted** loss, where it works perfectly and quietly props up a
;; decision you should have taken differently. Same code; only one of the two
;; uses is a good idea.
;; ---------------------------------------------------------------------------

(defn decode-data
  "Coerce one event's `:data` out of its wire representation.

  Unknown event types pass through untouched, for the same reason the schemas
  are open: this code will meet events it was not written for."
  [event-type data]
  (if-let [schema (get by-type event-type)]
    (m/decode schema data json->domain)
    data))

(defn valid-data?
  [event-type data]
  (if-let [schema (get by-type event-type)]
    (m/validate schema data)
    ;; Nothing to check against is not the same as invalid — see above.
    true))
