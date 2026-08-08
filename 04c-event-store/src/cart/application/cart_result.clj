(ns cart.application.cart-result
  "Stable public cart result data shared by command and query slices."
  (:require [cart.observation :as observation]))

(defn cart-result [key-ring projection]
  {:cart-id (str (:cart-id projection))
   :status (name (:status projection))
   :items (->> (:items projection)
               (sort-by (comp str key))
               (mapv (fn [[product-id quantity]]
                       {:product-id (str product-id)
                        :quantity quantity})))
   :cart-observation (observation/issue key-ring
                                        (:cart-id projection)
                                        (:revision projection))})
