(ns lab27.platform.bus
  "An in-process integration-message bus.

  It is intentionally tiny. The architectural point is the contract crossing
  the module boundary, not whether this delivery mechanism is an atom today
  or a broker after extraction. Delivery is at-least-once; subscribers own
  their idempotency.

  What it carries is a **delivery**: `{:headers … :message …}`. Every broker
  worth the name has that shape, because transport concerns and the message
  itself have different lifetimes. Lab 26 uses the headers for W3C trace
  context, so the message contract lab 25 wrote is still exactly the map lab 25
  wrote — a new transport concern did not get to widen a business contract."
  (:require [clojure.set :as set]))

(defrecord Bus [subscriptions])

(defn bus [] (->Bus (atom {})))

(defn subscribe!
  [bus message-type handler]
  (swap! (:subscriptions bus) update message-type (fnil conj []) handler)
  bus)

(defn publish!
  [bus {:keys [message] :as delivery}]
  (let [handlers (get @(:subscriptions bus) (:message/type message) [])]
    {:message/id (:message/id message)
     :delivered  (mapv #(% delivery) handlers)}))

(defn subscribed-types [bus]
  (set (keys @(:subscriptions bus))))

(defn handles? [bus message-types]
  (set/subset? (set message-types) (subscribed-types bus)))
