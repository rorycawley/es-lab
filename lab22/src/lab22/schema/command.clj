(ns lab22.schema.command
  "Command schemas — **closed**, because they guard a door.

  These describe what may arrive from outside: an HTTP body, a queue message,
  a form post. An unexpected key there is a client bug, a version mismatch or
  an attack, and none of those should be waved through. So `{:closed true}`.

  Contrast `lab22.schema.event`, which is open for exactly the opposite
  reason. Same library, opposite setting, and the direction of travel decides."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Flavour
  [:enum "vanilla" "chocolate" "strawberry" "pistachio"])

(def Quantity
  [:int {:min 1 :max 500}])

(def LoadTruck
  [:map {:closed true}
   [:command/id :uuid]
   [:command/type [:= :load-truck]]
   [:data [:map {:closed true}
           [:flavour Flavour]
           [:quantity Quantity]]]])

(def BuyFlavour
  [:map {:closed true}
   [:command/id :uuid]
   [:command/type [:= :buy-flavour]]
   [:data [:map {:closed true}
           [:flavour Flavour]]]])

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
  {:load-truck  LoadTruck
   :buy-flavour BuyFlavour})

(defn schema-for [command] (get by-type (:command/type command)))

(defn validate
  "`nil` if the command is well-formed, otherwise a humanised explanation."
  [command]
  (if-let [schema (schema-for command)]
    (when-not (m/validate schema command)
      (me/humanize (m/explain schema command)))
    {:command/type ["unknown command type"]}))
