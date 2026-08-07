(ns cart.app.query-test
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.app.query :as query]
            [cart.port.cart-query :as cart-query]
            [cart.port.event-store :as store]
            [clojure.test :refer [deftest is testing]]))

(defn- event [product-id quantity]
  {:type :cart.event/product-item-added
   :data {:cart-id "c1"
          :product-item {:product-id product-id
                         :quantity quantity
                         :unit-price 1299}
          :added-at 1735689600000}
   :metadata {:now 1735689600000}})

(deftest cart-summary-folds-query-state-behind-the-query-port
  (let [event-store (memory/make-store)
        queries     (query/make-event-store-query event-store)]
    (store/append-to-stream event-store
                            "shopping_cart-c1"
                            [(event "sku-1" 2)]
                            :stream-does-not-exist)
    (is (= {:cart-id   "c1"
            :stream-id "shopping_cart-c1"
            :exists?   true
            :version   1
            :state     {:status :opened
                        :product-items {"sku-1" 2}}}
           (cart-query/cart-summary queries "c1")))))

(deftest cart-events-exposes-stream-read-through-query-port
  (testing "HTTP can expose events without depending on the event-store port"
    (let [event-store (memory/make-store)
          queries     (query/make-event-store-query event-store)
          written     (event "sku-1" 2)]
      (store/append-to-stream event-store
                              "shopping_cart-c1"
                              [written]
                              :stream-does-not-exist)
      (is (= {:cart-id   "c1"
              :stream-id "shopping_cart-c1"
              :events    [written]
              :version   1
              :exists?   true}
             (cart-query/cart-events queries "c1"))))))
