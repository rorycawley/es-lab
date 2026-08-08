(ns cart.port.out.projection-store)

(defprotocol ProjectionStore
  (read-cart-view [store cart-id])
  (read-cart-history [store cart-id]))
