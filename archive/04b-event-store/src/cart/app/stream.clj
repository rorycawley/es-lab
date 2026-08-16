(ns cart.app.stream
  "Application-level stream naming. Kept outside adapters so all driving
   adapters route the same aggregate id to the same event stream.")

(defn shopping-cart-stream-id [cart-id]
  (str "shopping_cart-" cart-id))
