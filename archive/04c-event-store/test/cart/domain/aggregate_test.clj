(ns cart.domain.aggregate-test
  (:require [cart.domain.aggregate :as aggregate]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util UUID]))

(def cart-id (UUID/fromString "10000000-0000-0000-0000-000000000001"))
(def product-a (UUID/fromString "20000000-0000-0000-0000-000000000001"))

(defn add-event [revision quantity]
  {:event/type :product-item-added
   :event/version 1
   :event/revision revision
   :event/data {:cart-id cart-id :product-id product-a :quantity quantity}})

(deftest fold-reconstructs-additions-without-effects
  (is (= {:cart/existence :present
          :cart/id cart-id
          :cart/status :open
          :cart/items {product-a 5}
          :cart/revision 2}
         (aggregate/fold [(add-event 1 2) (add-event 2 3)]))))

(deftest add-decision-proposes-events-or-business-rejections
  (testing "first addition"
    (is (= 2
           (get-in (aggregate/decide-add-product-item
                    (aggregate/missing-cart)
                    {:cart-id cart-id :product-id product-a :quantity 2})
                   [:events 0 :event/data :quantity]))))
  (testing "resulting quantity limit"
    (let [state (aggregate/fold [(add-event 1 999)])]
      (is (= {:rejection {:code :product-quantity-limit-exceeded}}
             (aggregate/decide-add-product-item
              state
              {:cart-id cart-id :product-id product-a :quantity 2})))))
  (testing "closed cart"
    (is (= {:rejection {:code :cart-closed}}
           (aggregate/decide-add-product-item
            {:cart/existence :present
             :cart/id cart-id
             :cart/status :closed
             :cart/items {product-a 2}
             :cart/revision 2}
            {:cart-id cart-id :product-id product-a :quantity 1})))))

(deftest corrupt-history-is-not-silently-folded
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown cart event"
                        (aggregate/fold [{:event/type :unknown
                                          :event/version 1
                                          :event/revision 1}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"revision 1"
                        (aggregate/fold [(add-event 2 1)]))))
