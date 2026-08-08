(ns cart.app.command-test
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.app.command :as command]
            [cart.port.cart-command :as cart-command]
            [cart.port.event-store :as store]
            [clojure.test :refer [deftest is testing]]))

(def now 1735689600000)

(defn- add-cmd [product-id quantity]
  {:type :cart.command/add-product-item
   :data {:cart-id "c1"
          :product-item {:product-id product-id
                         :quantity quantity
                         :unit-price 1299}}
   :metadata {:now now}})

(def confirm-cmd
  {:type :cart.command/confirm
   :data {:cart-id "c1"}
   :metadata {:now now}})

(defn- new-command-port
  ([event-store]
   (command/make-event-store-command event-store))
  ([event-store retry]
   (command/make-event-store-command event-store retry)))

(defn- handle
  ([commands cart-id cmd]
   (cart-command/handle-cart-command commands cart-id cmd))
  ([commands cart-id cmd expected-version]
   (cart-command/handle-cart-command commands cart-id cmd expected-version)))

(deftest command-port-routes-cart-id-to-event-stream
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)
        [outcome data] (handle commands "c1" (add-cmd "sku-1" 2))]
    (is (= :ok outcome))
    (is (= "c1" (:cart-id data)))
    (is (= "shopping_cart-c1" (:stream-id data)))
    (is (= 1 (:version data)))
    (is (= [:cart.event/product-item-added]
           (mapv :type (:events (store/read-stream event-store
                                                   "shopping_cart-c1")))))))

(deftest command-port-appends-subsequent-events
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)]
    (handle commands "c1" (add-cmd "sku-1" 2))
    (let [[outcome data] (handle commands "c1" confirm-cmd)]
      (is (= :ok outcome))
      (is (= 2 (:version data))))))

(deftest command-port-returns-business-rule-failures
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)
        [outcome data] (handle commands "c1" confirm-cmd)]
    (is (= :error outcome))
    (is (= :not-opened (:reason data)))))

(deftest command-port-does-not-write-rejected-commands
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)]
    (handle commands "c1" confirm-cmd)
    (is (= {:events [] :version 0 :exists? false}
           (store/read-stream event-store "shopping_cart-c1")))))

(deftest command-port-preserves-explicit-expected-version
  (testing "the command use case owns expected-version dispatch to the stream handler"
    (let [event-store (memory/make-store)
          commands    (new-command-port event-store)]
      (handle commands "c1" (add-cmd "sku-1" 2) :stream-does-not-exist)
      (is (= [:conflict {:expected :stream-does-not-exist :current 1}]
             (handle commands "c1" (add-cmd "sku-2" 1) :stream-does-not-exist))))))

(deftest command-port-surfaces-stale-version-conflicts
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)]
    (handle commands "c1" (add-cmd "sku-1" 2))
    (let [[outcome data] (handle commands "c1" confirm-cmd 0)]
      (is (= :conflict outcome))
      (is (= {:expected 0 :current 1} data)))))

(deftest command-port-does-not-retry-by-default
  (testing "SPEC R4.8 — a conflict surfaces rather than being silently retried"
    (let [event-store (memory/make-store)
          commands    (new-command-port event-store)]
      (handle commands "c1" (add-cmd "sku-1" 2))
      (is (= :conflict (first (handle commands "c1" confirm-cmd 0)))))))

(defn- flaky-store
  "Wraps a store, forcing the first `n` appends to come back as conflicts.
   Counts appends so a test can prove the cycle re-ran rather than the append."
  [inner n]
  (let [appends (atom 0)]
    [(reify store/EventStore
       (read-stream [_ stream-id] (store/read-stream inner stream-id))
       (append-to-stream [_ stream-id events expected]
         (if (<= (swap! appends inc) n)
           [:conflict {:expected expected :current 999}]
           (store/append-to-stream inner stream-id events expected))))
     appends]))

(deftest command-port-retry-re-runs-the-cycle-and-eventually-succeeds
  (testing "a conflict that clears within the budget resolves without the caller seeing it"
    (let [[event-store appends] (flaky-store (memory/make-store) 2)
          commands (new-command-port event-store {:min-timeout 1})
          [outcome data] (handle commands "c1" (add-cmd "sku-1" 2))]
      (is (= :ok outcome))
      (is (= 1 (:version data)))
      (is (= 3 @appends) "two conflicts, then the winning append"))))

(deftest command-port-retry-gives-up-after-the-configured-budget
  (testing "retry is bounded — a permanent conflict surfaces as a conflict"
    (let [[event-store appends] (flaky-store (memory/make-store) 999)
          commands (new-command-port event-store {:retries 3 :min-timeout 1})
          [outcome _] (handle commands "c1" (add-cmd "sku-1" 2))]
      (is (= :conflict outcome))
      (is (= 4 @appends) "the initial attempt plus exactly 3 retries"))))

(deftest command-port-does-not-retry-caller-pinned-version
  (testing "retry re-runs against fresh state, but a pinned version stays pinned"
    (let [[event-store appends] (flaky-store (memory/make-store) 999)
          commands (new-command-port event-store {:retries 3 :min-timeout 1})
          [outcome _] (handle commands "c1" (add-cmd "sku-1" 2) 0)]
      (is (= :conflict outcome))
      (is (= 1 @appends) "attempted once, not four times"))))

(deftest command-port-carries-command-metadata-to-events
  (testing "provenance is attached by the application shell, not by cart.core"
    (let [event-store (memory/make-store)
          commands    (new-command-port event-store)
          [_ data]    (handle commands "c1" (add-cmd "sku-1" 2))]
      (is (= [{:now now}] (mapv :metadata (:events data))))
      (is (= [{:now now}]
             (mapv :metadata (:events (store/read-stream event-store
                                                         "shopping_cart-c1"))))))))

(deftest command-port-stops-retrying-when-backoff-is-interrupted
  (testing "the interrupt must not escape as an exception, and must not be swallowed"
    (let [[event-store appends] (flaky-store (memory/make-store) 999)
          commands (new-command-port event-store {:retries 3 :min-timeout 5000})
          outcome  (promise)
          flagged  (promise)
          t        (Thread. #(do (deliver outcome
                                          (try (first (handle commands
                                                              "c1"
                                                              (add-cmd "sku-1" 2)))
                                               (catch Throwable e [:threw e])))
                                 (deliver flagged (.isInterrupted
                                                   (Thread/currentThread)))))]
      (.start t)
      (Thread/sleep 300)
      (.interrupt t)
      (.join t 5000)

      (is (= :conflict @outcome)
          "the conflict it already had is a real answer, not an exception")
      (is (true? @flagged) "the interrupt flag must be restored, not cleared")
      (is (= 1 @appends) "it stopped rather than burning the rest of the budget")
      (is (not (.isAlive t))))))

(deftest command-port-rebuilds-state-from-events-each-time
  (let [event-store (memory/make-store)
        commands    (new-command-port event-store)]
    (handle commands "c1" (add-cmd "sku-1" 2))
    (handle commands "c1" (add-cmd "sku-2" 1))
    (let [[outcome _] (handle commands "c1" confirm-cmd)]
      (is (= :ok outcome)
          "confirm needs the cart to be open, which only the events say"))))
