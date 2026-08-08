(ns cart.slice.view-cart.handler-test
  (:require [cart.adapter.out.persistence.memory :as memory]
            [cart.slice.add-product-item.handler :as add-handler]
            [cart.slice.add-product-item.port :as add-port]
            [cart.slice.view-cart.handler :as view-handler]
            [cart.slice.view-cart.port :as view-port]
            [clojure.test :refer [deftest is]])
  (:import [java.time Instant]
           [java.util UUID]))

(def key-ring {:active-key-id "test"
               :keys {"test" "iteration-one-test-signing-key"}})

(deftest view-cart-reads-the-projection-produced-by-add
  (let [store    (memory/new-store)
        ids      (atom [(UUID/fromString "abcdefab-cdef-abcd-efab-cdefabcdefab")
                        (UUID/fromString "40000000-0000-0000-0000-000000000001")])
        add      (add-handler/new-handler
                  {:event-store store
                   :idempotency-store store
                   :unit-of-work store
                   :key-ring key-ring
                   :uuid-fn #(let [id (first @ids)] (swap! ids subvec 1) id)
                   :clock #(Instant/parse "2026-01-01T00:00:00Z")})
        view     (view-handler/new-handler {:projection-store store
                                            :key-ring key-ring})
        added    (add-port/add-product-item
                  add
                  {:request-id "30000000-0000-0000-0000-000000000001"
                   :product-item
                   {:product-id "20000000-0000-0000-0000-000000000001"
                    :quantity 2}})
        viewed   (view-port/view-cart view {:cart-id (get-in added [:result :cart-id])})]
    (is (= :success (:outcome viewed)))
    (is (= (select-keys (:result added) [:cart-id :status :items])
           (select-keys (:result viewed) [:cart-id :status :items])))
    (is (= :success
           (:outcome (view-port/view-cart
                      view {:cart-id (.toUpperCase
                                      (get-in added [:result :cart-id]))}))))
    (is (= :invalid
           (:outcome (view-port/view-cart view {:cart-id "1-1-1-1-1"}))))
    (is (= :invalid (:outcome (view-port/view-cart view {:cart-id "missing"}))))))
