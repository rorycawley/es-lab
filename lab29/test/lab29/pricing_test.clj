(ns lab29.pricing-test
  "A difficult rule extracted from a slice and tested directly as values."
  (:require [clojure.test :refer [deftest is]]
            [lab29.ordering.place-order :as place-order]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000025")
(def order-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")

(deftest price-order-is-pure-business-behaviour-test
  (is (= {:order-id order-1
          :product-id vanilla
          :product-name "vanilla"
          :quantity 3
          :unit-price-cents 275
          :total-cents 825}
         (place-order/price-order
          {:order-id order-1 :product-id vanilla :quantity 3}
          {:product-name "vanilla" :current-price-cents 275}))))
