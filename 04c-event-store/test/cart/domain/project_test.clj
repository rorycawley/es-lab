(ns cart.domain.project-test
  (:require [cart.domain.project :as project]
            [clojure.test :refer [deftest is]])
  (:import [java.time Instant]
           [java.util UUID]))

(def cart-id (UUID/fromString "10000000-0000-0000-0000-000000000001"))
(def product-id (UUID/fromString "20000000-0000-0000-0000-000000000001"))
(def accepted-at (Instant/parse "2026-01-01T00:00:00Z"))
(def event {:event/type :product-item-added
            :event/version 1
            :event/revision 1
            :event/accepted-at accepted-at
            :event/data {:cart-id cart-id :product-id product-id :quantity 2}})

(deftest projectors-create-query-models-from-an-event
  (is (= {:cart-id cart-id
          :revision 1
          :status :open
          :items {product-id 2}}
         (project/cart-view nil event)))
  (is (= {:cart-id cart-id
          :revision 1
          :status :open
          :items {product-id 2}}
         (project/cart-view-from-state
          {:cart/existence :present
           :cart/id cart-id
           :cart/revision 1
           :cart/status :open
           :cart/items {product-id 2}})))
  (is (= {:cart-id cart-id
          :revision 1
          :change-type :product-item-added
          :accepted-at accepted-at
          :business-data {:product-id product-id :quantity 2}}
         (project/history-entry event))))
