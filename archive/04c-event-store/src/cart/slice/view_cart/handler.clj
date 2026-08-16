(ns cart.slice.view-cart.handler
  "Projection-only view-cart query handler."
  (:require [cart.application.cart-result :as cart-result]
            [cart.application.input :as input]
            [cart.observation :as observation]
            [cart.port.out.projection-store :as projection-store]
            [cart.slice.view-cart.port :as port]))

(def ^:private request-keys #{:cart-id})

(defrecord Handler [projection-store key-ring]
  port/ViewCart
  (view-cart [_ request]
    (let [supplied (if (map? request) (set (keys request)) #{})
          cart-id  (when (= request-keys supplied)
                     (input/parse-uuid (:cart-id request)))]
      (if-not cart-id
        {:outcome :invalid
         :code :invalid-cart
         :field-errors [{:field "cart-id" :code :invalid-cart}]}
        (if-let [projection (projection-store/read-cart-view projection-store
                                                             cart-id)]
          {:outcome :success
           :result (cart-result/cart-result key-ring projection)}
          {:outcome :invalid
           :code :invalid-cart
           :field-errors [{:field "cart-id" :code :invalid-cart}]})))))

(defn new-handler [{:keys [projection-store key-ring]}]
  (when-not projection-store
    (throw (ex-info "Missing view-cart dependency"
                    {:dependency :projection-store})))
  (observation/validate-key-ring! key-ring)
  (->Handler projection-store key-ring))
