(ns lab25.platform.bus
  "An in-process integration-message bus.

  It is intentionally tiny. The architectural point is the contract crossing
  the module boundary, not whether this delivery mechanism is an atom today
  or a broker after extraction. Delivery is at-least-once; subscribers own
  their idempotency."
  (:require [clojure.set :as set]))

(defrecord Bus [subscriptions])

(defn bus [] (->Bus (atom {})))

(defn subscribe!
  [bus message-type handler]
  (swap! (:subscriptions bus) update message-type (fnil conj []) handler)
  bus)

(defn publish!
  [bus message]
  (let [handlers (get @(:subscriptions bus) (:message/type message) [])]
    {:message/id (:message/id message)
     :delivered  (mapv #(% message) handlers)}))

(defn subscribed-types [bus]
  (set (keys @(:subscriptions bus))))

(defn handles? [bus message-types]
  (set/subset? (set message-types) (subscribed-types bus)))
