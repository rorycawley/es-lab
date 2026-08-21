(ns lab22.schema.command
  "Inbound-message and command schemas — **closed**, because they guard a door.

  The message schemas describe what may arrive from outside: an HTTP body, a
  queue message, a form post. Internal commands additionally permit the
  application-owned target address used by policies. Both shapes are closed:
  an unexpected key is a client bug, version mismatch or attack.

  Contrast `lab22.schema.event`, which is open for exactly the opposite
  reason. Same library, opposite setting, and the direction of travel decides."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Flavour
  [:enum "vanilla" "chocolate" "strawberry" "pistachio"])

(def Quantity
  [:int {:min 1 :max 500}])

(def LoadTruckData
  [:map {:closed true}
   [:flavour Flavour]
   [:quantity Quantity]])

(def BuyFlavourData
  [:map {:closed true}
   [:flavour Flavour]])

(def AddressedLoadTruckData
  [:map {:closed true}
   [:truck-id {:optional true} :uuid]
   [:flavour Flavour]
   [:quantity Quantity]])

(def LoadTruckMessage
  [:map {:closed true}
   [:type [:= :load-truck]]
   [:data LoadTruckData]])

(def EnsureStockMessage
  [:map {:closed true}
   [:type [:= :ensure-stock]]
   [:data LoadTruckData]])

(def BuyFlavourMessage
  [:map {:closed true}
   [:type [:= :buy-flavour]]
   [:data BuyFlavourData]])

(def LoadTruck
  [:map {:closed true}
   [:command/id :uuid]
   [:correlation-id :uuid]
   [:command/type [:= :load-truck]]
   [:data AddressedLoadTruckData]])

(def EnsureStock
  [:map {:closed true}
   [:command/id :uuid]
   [:correlation-id :uuid]
   [:command/type [:= :ensure-stock]]
   [:data AddressedLoadTruckData]])

(def BuyFlavour
  [:map {:closed true}
   [:command/id :uuid]
   [:correlation-id :uuid]
   [:command/type [:= :buy-flavour]]
   [:data BuyFlavourData]])

;; ---------------------------------------------------------------------------
;; What is NOT here, and must never be.
;;
;;     [:fn (fn [cmd] (pos? (stock-of (:flavour cmd))))]
;;
;; Malli will happily accept that, and lab 2's whole distinction dies the
;; moment it does. "Is there enough vanilla?" is context-*dependent*: it can
;; only be answered against state, at the moment of the decision, by `decide`.
;; The inbound schema runs before the command exists and has nothing to
;; consult, so it
;; would consult something stale or nothing at all.
;;
;; The schema's job is `:quantity` is a positive integer. Whether the depot can
;; cover it is a different question, asked somewhere else, by lab 8.
;; ---------------------------------------------------------------------------

(def by-type
  {:load-truck  LoadTruck
   :ensure-stock EnsureStock
   :buy-flavour BuyFlavour})

(def message-by-type
  {:load-truck  LoadTruckMessage
   :ensure-stock EnsureStockMessage
   :buy-flavour BuyFlavourMessage})

(defn schema-for [command] (get by-type (:command/type command)))

(defn message-schema-for [message] (get message-by-type (:type message)))

(defn validate-message
  "`nil` if an inbound message is well-formed, otherwise an explanation.

  This runs before the adapter allocates command/correlation ids or constructs
  the internal command value."
  [message]
  (if-let [schema (message-schema-for message)]
    (when-not (m/validate schema message)
      (me/humanize (m/explain schema message)))
    {:type ["unknown command type"]}))

(defn validate
  "Validate an already-constructed internal command.

  Kept for tests and other trusted callers that work directly with command
  values; untrusted intake uses `validate-message` first."
  [command]
  (if-let [schema (schema-for command)]
    (when-not (m/validate schema command)
      (me/humanize (m/explain schema command)))
    {:command/type ["unknown command type"]}))
