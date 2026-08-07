(ns cart.app.command-test
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.app.command :as command]
            [cart.port.cart-command :as cart-command]
            [cart.port.event-store :as store]
            [clojure.test :refer [deftest is testing]]))

(defn- add-cmd [product-id quantity]
  {:type :cart.command/add-product-item
   :data {:cart-id "c1"
          :product-item {:product-id product-id
                         :quantity quantity
                         :unit-price 1299}}
   :metadata {:now 1735689600000}})

(deftest cart-command-routes-cart-id-to-event-stream
  (let [event-store (memory/make-store)
        commands    (command/make-event-store-command event-store)
        [outcome data] (cart-command/handle-cart-command
                        commands
                        "c1"
                        (add-cmd "sku-1" 2))]
    (is (= :ok outcome))
    (is (= "c1" (:cart-id data)))
    (is (= "shopping_cart-c1" (:stream-id data)))
    (is (= 1 (:version data)))
    (is (= [:cart.event/product-item-added]
           (mapv :type (:events (store/read-stream event-store
                                                   "shopping_cart-c1")))))))

(deftest cart-command-preserves-explicit-expected-version
  (testing "the command use case owns expected-version dispatch to the stream handler"
    (let [event-store (memory/make-store)
          commands    (command/make-event-store-command event-store)]
      (cart-command/handle-cart-command commands
                                        "c1"
                                        (add-cmd "sku-1" 2)
                                        :stream-does-not-exist)
      (is (= [:conflict {:expected :stream-does-not-exist :current 1}]
             (cart-command/handle-cart-command commands
                                               "c1"
                                               (add-cmd "sku-2" 1)
                                               :stream-does-not-exist))))))
