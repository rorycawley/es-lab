(ns cart.app.query
  "Query handlers. Separate from command handling for CQRS, but still an
   application use-case boundary rather than HTTP code reaching into core."
  (:require [cart.app.stream :as stream]
            [cart.core :as core]
            [cart.port.cart-query :as query]
            [cart.port.event-store :as store]))

(defrecord EventStoreCartQuery [event-store]
  query/CartQuery

  (cart-summary [_ cart-id]
    (let [stream-id (stream/shopping-cart-stream-id cart-id)
          read      (store/read-stream event-store stream-id)]
      {:cart-id   cart-id
       :stream-id stream-id
       :exists?   (:exists? read)
       :version   (:version read)
       :state     (core/fold (:events read))}))

  (cart-events [_ cart-id]
    (let [stream-id (stream/shopping-cart-stream-id cart-id)]
      (assoc (store/read-stream event-store stream-id)
             :cart-id cart-id
             :stream-id stream-id))))

(defn make-event-store-query [event-store]
  (->EventStoreCartQuery event-store))
