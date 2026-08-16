(ns cart.app.command
  "Cart command use cases. This is the command-side CQRS boundary for driving
   adapters: route cart ids are translated to streams here, not in HTTP."
  (:require [cart.app.handle :as handle]
            [cart.app.stream :as stream]
            [cart.port.cart-command :as command]))

(defn- decorate-success [cart-id stream-id [outcome data]]
  (case outcome
    :ok       [:ok (assoc data :cart-id cart-id :stream-id stream-id)]
    :error    [:error data]
    :conflict [:conflict data]))

(defn- handle* [event-store retry cart-id command expected-version]
  (let [stream-id (stream/shopping-cart-stream-id cart-id)
        deps      {:event-store event-store :retry retry}]
    (decorate-success
     cart-id
     stream-id
     (if (nil? expected-version)
       (handle/handle-command deps stream-id command)
       (handle/handle-command deps stream-id command expected-version)))))

(defrecord EventStoreCartCommand [event-store retry]
  command/CartCommand

  (handle-cart-command [_ cart-id command]
    (handle* event-store retry cart-id command nil))

  (handle-cart-command [_ cart-id command expected-version]
    (handle* event-store retry cart-id command expected-version)))

(defn make-event-store-command
  ([event-store]
   (make-event-store-command event-store nil))
  ([event-store retry]
   (->EventStoreCartCommand event-store retry)))
