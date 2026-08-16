(ns cart.adapter.driven.event-store-memory-test
  (:require [cart.adapter.driven.event-store-contract :as contract]
            [cart.adapter.driven.event-store-memory :as mem]
            [clojure.test :refer [deftest]]))

(deftest satisfies-the-event-store-contract
  (contract/verify mem/make-store #(str "shopping_cart-" (random-uuid))))
