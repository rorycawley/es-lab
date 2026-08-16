(ns cart.slice.view-cart.port)

(defprotocol ViewCart
  (view-cart [handler request]))
