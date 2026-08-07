(ns cart.port.cart-query
  "Query-side port. Driving adapters ask application query handlers for read
   data; they do not fold the domain core or read the event store directly.")

(defprotocol CartQuery
  (cart-summary [this cart-id]
    "=> {:cart-id ... :stream-id ... :exists? boolean :version n :state {...}}")

  (cart-events [this cart-id]
    "=> {:cart-id ... :stream-id ... :events [...] :version n :exists? boolean}"))
