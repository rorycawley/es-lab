(ns lab24.schema.command
  "Command schemas — **closed**, because they guard a door.

  These describe what may arrive from outside: an HTTP body, a queue message,
  a form post. An unexpected key there is a client bug, a version mismatch or
  an attack, and none of those should be waved through. So `{:closed true}`.

  Contrast `lab24.schema.event`, which is open for exactly the opposite
  reason. Same library, opposite setting, and the direction of travel decides."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

(def Flavour
  [:enum "vanilla" "chocolate" "strawberry" "pistachio"])

(def Quantity
  [:int {:min 1 :max 500}])

;; ---------------------------------------------------------------------------
;; The actor, and why `{:closed true}` above is a security control
;;
;; ADR-0020: \"Roles are extracted from the trusted OIDC session claims — never
;; from the request body.\"
;;
;; Two things enforce that here, and neither is a check anybody wrote.
;;
;; The adapter builds `:command/actor` from the verified principal; it never
;; copies it from the message, so there is no path for a client-supplied actor
;; to arrive. And `:data` is closed, so a body that tries — `{\"flavour\":
;; \"vanilla\", \"actor\": {\"id\": \"USR-ADMIN\"}}` — is rejected as malformed
;; before anything reads it.
;;
;; Lab 22 closed these maps because \"an unexpected key is a bug or an attack\".
;; This is the lab where the second half of that sentence gets a test.
;; ---------------------------------------------------------------------------

;; Strings, and an enum of strings — which is the whole fix for a problem this
;; repository had paid for three times before noticing the cause.
;;
;; Compare `:flavour` above. Both are small closed vocabularies; one is a
;; keyword and one is not, and only one of them needs a decoder to survive a
;; round trip through JSONB. The difference is not a property of JSON. It is a
;; choice made when the event was designed.
;;
;; Contrast with `:event/type`, which *is* a keyword in the domain and stored
;; as TEXT in its own column, coerced back at exactly one place — the point of
;; dispatch. That is where a keyword pays for itself, because the code branches
;; on it. Nothing branches on an actor.
(def Actor
  [:map {:closed true}
   [:type [:enum "user" "system"]]
   [:id :string]])

(def LoadTruck
  [:map {:closed true}
   [:command/id :uuid]
   [:command/type [:= :load-truck]]
   [:command/actor Actor]
   [:data [:map {:closed true}
           [:flavour Flavour]
           [:quantity Quantity]]]])

(def BuyFlavour
  [:map {:closed true}
   [:command/id :uuid]
   [:command/type [:= :buy-flavour]]
   [:command/actor Actor]
   [:data [:map {:closed true}
           [:flavour Flavour]]]])

(def AssignDriver
  [:map {:closed true}
   [:command/id :uuid]
   [:command/type [:= :assign-driver]]
   [:command/actor Actor]
   [:data [:map {:closed true}
           [:driver-id :string]]]])

;; ---------------------------------------------------------------------------
;; What is NOT here, and must never be.
;;
;;     [:fn (fn [cmd] (pos? (stock-of (:flavour cmd))))]
;;
;; Malli will happily accept that, and lab 2's whole distinction dies the
;; moment it does. "Is there enough vanilla?" is context-*dependent*: it can
;; only be answered against state, at the moment of the decision, by `decide`.
;; A schema runs before the command exists and has nothing to consult, so it
;; would consult something stale or nothing at all.
;;
;; The schema's job is `:quantity` is a positive integer. Whether the depot can
;; cover it is a different question, asked somewhere else, by lab 8.
;; ---------------------------------------------------------------------------

(def by-type
  {:load-truck    LoadTruck
   :buy-flavour   BuyFlavour
   :assign-driver AssignDriver})

(defn schema-for [command] (get by-type (:command/type command)))

;; ---------------------------------------------------------------------------
;; Decoding, at the *inbound* wire boundary.
;;
;; Lab 22 used a schema to decode events coming out of the store, because JSONB
;; has no keyword type. An HTTP body has no keyword type either — a client
;; sends `{"flavour": "vanilla"}` and the schema expects `"vanilla"`.
;;
;; Same loss, same fix, opposite direction. Decode first, then validate:
;; validating the wire form would reject every well-formed request, and
;; decoding without validating would coerce nonsense into plausible values.
;; ---------------------------------------------------------------------------

(def ^:private json->domain (mt/json-transformer))

(defn decode
  "Coerce a command out of its wire representation. Unknown types pass through
  untouched, so `validate` can be the one place that rejects them."
  [command]
  (if-let [schema (schema-for command)]
    (m/decode schema command json->domain)
    command))

(defn validate
  "`nil` if the command is well-formed, otherwise a humanised explanation."
  [command]
  (if-let [schema (schema-for command)]
    (when-not (m/validate schema command)
      (me/humanize (m/explain schema command)))
    {:command/type ["unknown command type"]}))
