(ns cart.slice.add-product-item.handler-test
  (:require [cart.adapter.out.persistence.memory :as memory]
            [cart.port.out.event-store :as event-store]
            [cart.slice.add-product-item.handler :as handler]
            [cart.slice.add-product-item.port :as port]
            [clojure.test :refer [deftest is]])
  (:import [java.time Instant]
           [java.util UUID]))

(def key-ring {:active-key-id "test"
               :keys {"test" "iteration-one-test-signing-key"}})
(def product-a "20000000-0000-0000-0000-000000000001")
(def product-b "20000000-0000-0000-0000-000000000002")
(def request-a "30000000-0000-0000-0000-000000000001")
(def request-b "30000000-0000-0000-0000-000000000002")
(def case-request "abcdefab-cdef-abcd-efab-cdefabcdefac")
(def case-product "abcdefab-cdef-abcd-efab-cdefabcdefab")
(def cart-id (UUID/fromString "10000000-0000-0000-0000-000000000001"))

(defn fixture []
  (let [store (memory/new-store)
        ids   (atom [cart-id
                     (UUID/fromString "40000000-0000-0000-0000-000000000001")
                     (UUID/fromString "40000000-0000-0000-0000-000000000002")
                     (UUID/fromString "40000000-0000-0000-0000-000000000003")])]
    {:store store
     :handler (handler/new-handler
               {:event-store store
                :idempotency-store store
                :unit-of-work store
                :key-ring key-ring
                :uuid-fn #(let [id (first @ids)] (swap! ids subvec 1) id)
                :clock #(Instant/parse "2026-01-01T00:00:00Z")})}))

(defn random-fixture []
  (let [store (memory/new-store)]
    {:store store
     :handler (handler/new-handler
               {:event-store store
                :idempotency-store store
                :unit-of-work store
                :key-ring key-ring
                :uuid-fn #(UUID/randomUUID)
                :clock #(Instant/parse "2026-01-01T00:00:00Z")})}))

(defn first-add [request-id product-id quantity]
  {:request-id request-id
   :product-item {:product-id product-id :quantity quantity}})

(defn existing-add [request-id result product-id quantity]
  {:request-id request-id
   :cart-id (:cart-id result)
   :cart-observation (:cart-observation result)
   :product-item {:product-id product-id :quantity quantity}})

(deftest first-and-existing-additions-are-event-sourced
  (let [{:keys [store handler]} (fixture)
        first-result (:result (port/add-product-item
                               handler (first-add request-a product-a 2)))
        second       (port/add-product-item
                      handler (existing-add request-b first-result product-a 3))]
    (is (= :success (:outcome second)))
    (is (= [{:product-id product-a :quantity 5}]
           (get-in second [:result :items])))
    (is (= 2 (:revision (event-store/read-stream store cart-id))))
    (is (= 2 (count (get-in (memory/snapshot store)
                            [:cart-history cart-id]))))))

(deftest accepted-command-replays-the-original-result
  (let [{:keys [store handler]} (fixture)
        command  (first-add request-a product-a 2)
        original (port/add-product-item handler command)
        changed  (port/add-product-item
                  handler (existing-add request-b (:result original) product-b 1))
        replayed (port/add-product-item handler command)]
    (is (= original replayed))
    (is (= 2 (count (get-in (memory/snapshot store) [:streams cart-id :events]))))
    (is (= 2 (count (get-in changed [:result :items]))))))

(deftest request-id-misuse-and-invalid-attempts-create-no-state
  (let [{:keys [store handler]} (fixture)
        invalid (port/add-product-item handler (first-add request-a product-a 0))]
    (is (= :invalid (:outcome invalid)))
    (is (empty? (:command-requests (memory/snapshot store))))
    (is (= :success (:outcome (port/add-product-item
                               handler (first-add request-a product-a 2)))))
    (is (= :invalid (:outcome (port/add-product-item
                               handler (first-add request-a product-b 2)))))))

(deftest uuid-input-requires-canonical-text-and-normalizes-letter-case
  (let [{:keys [store handler]} (fixture)]
    (is (= :invalid
           (:outcome (port/add-product-item
                      handler (first-add "1-1-1-1-1" product-a 2)))))
    (is (= :invalid
           (:outcome (port/add-product-item
                      handler (first-add case-request "2-2-2-2-2" 2)))))
    (is (empty? (:command-requests (memory/snapshot store))))
    (let [uppercase (first-add (.toUpperCase case-request)
                               (.toUpperCase case-product)
                               2)
          original  (port/add-product-item handler uppercase)
          replayed  (port/add-product-item
                     handler (first-add case-request case-product 2))]
      (is (= :success (:outcome original)))
      (is (= original replayed)))))

(deftest identical-concurrent-deliveries-commit-once
  (let [{:keys [store handler]} (fixture)
        command (first-add request-a product-a 2)
        start   (promise)
        submit  #(future @start (port/add-product-item handler command))
        left    (submit)
        right   (submit)]
    (deliver start true)
    (is (= @left @right))
    (is (= 1 (count (:streams (memory/snapshot store)))))
    (is (= 1 (count (get-in (memory/snapshot store)
                            [:streams cart-id :events]))))))

(deftest observation-authenticity-and-currency-are-enforced
  (let [{:keys [handler]} (fixture)
        first-result (:result (port/add-product-item
                               handler (first-add request-a product-a 2)))
        forged (assoc (existing-add request-b first-result product-a 1)
                      :cart-observation "forged")]
    (is (= :invalid (:outcome (port/add-product-item handler forged))))
    (is (= :invalid
           (:outcome
            (port/add-product-item
             handler
             (assoc (existing-add request-b first-result product-a 1)
                    :cart-id "10000000-0000-0000-0000-000000000099")))))
    (let [accepted (port/add-product-item
                    handler (existing-add request-b first-result product-a 1))]
      (is (= :success (:outcome accepted)))
      (is (= :conflict
             (:outcome
              (port/add-product-item
               handler
               (existing-add "30000000-0000-0000-0000-000000000003"
                             first-result product-a 1))))))))

(deftest non-equal-concurrent-first-additions-have-one-global-winner
  (let [{:keys [store handler]} (random-fixture)
        start (promise)
        left  (future @start
                      (port/add-product-item
                       handler (first-add request-a product-a 2)))
        right (future @start
                      (port/add-product-item
                       handler (first-add request-a product-b 2)))]
    (deliver start true)
    (is (= #{:success :invalid} #{(:outcome @left) (:outcome @right)}))
    (is (= 1 (count (:streams (memory/snapshot store)))))
    (is (= 1 (count (:command-requests (memory/snapshot store)))))))

(deftest identical-concurrent-existing-additions-have-one-result
  (let [{:keys [store handler]} (random-fixture)
        first-result (:result (port/add-product-item
                               handler (first-add request-a product-a 1)))
        command (existing-add request-b first-result product-a 2)
        start   (promise)
        left    (future @start (port/add-product-item handler command))
        right   (future @start (port/add-product-item handler command))]
    (deliver start true)
    (is (= @left @right))
    (is (= 2 (count (get-in (memory/snapshot store)
                            [:streams
                             (UUID/fromString (:cart-id first-result))
                             :events]))))))

(deftest generated-cart-id-collision-retries-without-partial-state
  (let [{seed-store :store seed-handler :handler} (random-fixture)
        seeded (:result (port/add-product-item
                         seed-handler (first-add request-a product-a 1)))
        occupied (UUID/fromString (:cart-id seeded))
        available (UUID/fromString "10000000-0000-0000-0000-000000000099")
        ids (atom [occupied
                   (UUID/randomUUID)
                   available
                   (UUID/randomUUID)])
        retrying (handler/new-handler
                  {:event-store seed-store
                   :idempotency-store seed-store
                   :unit-of-work seed-store
                   :key-ring key-ring
                   :uuid-fn #(let [id (first @ids)]
                               (swap! ids subvec 1)
                               id)
                   :clock #(Instant/parse "2026-01-01T00:00:00Z")})
        result (port/add-product-item
                retrying (first-add request-b product-b 1))]
    (is (= :success (:outcome result)))
    (is (= (str available) (get-in result [:result :cart-id])))
    (is (= 2 (count (:streams (memory/snapshot seed-store)))))
    (is (= 2 (count (:command-requests (memory/snapshot seed-store)))))))
