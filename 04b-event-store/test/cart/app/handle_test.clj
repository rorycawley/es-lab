(ns cart.app.handle-test
  "Application service against the in-memory store — fast, no Docker."
  (:require [cart.adapter.driven.event-store-memory :as mem]
            [cart.app.handle :as handle]
            [cart.core :as core]
            [cart.port.event-store :as store]
            [clojure.test :refer [deftest is testing]]))

(def now 1735689600000)

(defn- deps [] {:event-store (mem/make-store)})

(defn- add-cmd [product-id quantity]
  {:type :cart.command/add-product-item
   :data {:cart-id "c1"
          :product-item {:product-id product-id :quantity quantity :unit-price 1999}}
   :metadata {:now now}})

(def confirm-cmd
  {:type :cart.command/confirm :data {:cart-id "c1"} :metadata {:now now}})

(deftest first-command-creates-the-stream
  (let [d (deps)
        [outcome data] (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))]
    (is (= :ok outcome))
    (is (= 1 (:version data)))
    (is (true? (:created-new-stream? data)))))

(deftest second-command-appends
  (let [d (deps)]
    (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))
    (let [[outcome data] (handle/handle-command d "shopping_cart-c1" confirm-cmd)]
      (is (= :ok outcome))
      (is (= 2 (:version data))))))

(deftest business-rule-failures-come-back-as-errors
  (let [d (deps)
        [outcome data] (handle/handle-command d "shopping_cart-c1" confirm-cmd)]
    (is (= :error outcome))
    (is (= :not-opened (:reason data)))))

(deftest a-rejected-command-writes-nothing
  (let [d (deps)]
    (handle/handle-command d "shopping_cart-c1" confirm-cmd)
    (is (= {:events [] :version 0 :exists? false}
           (store/read-stream (:event-store d) "shopping_cart-c1")))))

(deftest stale-expected-version-conflicts
  (let [d (deps)]
    (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))
    (let [[outcome data] (handle/handle-command d "shopping_cart-c1" confirm-cmd 0)]
      (is (= :conflict outcome))
      (is (= {:expected 0 :current 1} data)))))

(deftest retry-is-off-by-default
  (testing "SPEC R4.8 — a conflict surfaces rather than being silently retried"
    (let [d (deps)]
      (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))
      (is (= :conflict (first (handle/handle-command d "shopping_cart-c1" confirm-cmd 0)))))))

;; ---------------------------------------------------------------------------
;; SPEC R4.8 — the retry loop itself
;; ---------------------------------------------------------------------------

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

(deftest retry-re-runs-the-cycle-and-eventually-succeeds
  (testing "a conflict that clears within the budget resolves without the
            caller seeing it"
    (let [[es appends] (flaky-store (mem/make-store) 2)
          d {:event-store es :retry {:min-timeout 1}}
          [outcome data] (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))]
      (is (= :ok outcome))
      (is (= 1 (:version data)))
      (is (= 3 @appends) "two conflicts, then the winning append"))))

(deftest retry-gives-up-after-the-configured-budget
  (testing "retry is bounded — a permanent conflict surfaces as a conflict"
    (let [[es appends] (flaky-store (mem/make-store) 999)
          d {:event-store es :retry {:retries 3 :min-timeout 1}}
          [outcome _] (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))]
      (is (= :conflict outcome))
      (is (= 4 @appends) "the initial attempt plus exactly 3 retries"))))

(deftest a-caller-pinned-version-is-not-retried
  (testing "SPEC R4.8 — retry re-runs against FRESH state, but a pinned version
            is re-applied unchanged, so every attempt conflicts identically.
            Retrying it would only add latency to a foregone answer."
    (let [[es appends] (flaky-store (mem/make-store) 999)
          d {:event-store es :retry {:retries 3 :min-timeout 1}}
          [outcome _] (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3) 0)]
      (is (= :conflict outcome))
      (is (= 1 @appends) "attempted once, not four times"))))

;; ---------------------------------------------------------------------------
;; Command metadata becomes event metadata
;; ---------------------------------------------------------------------------

(deftest command-metadata-is-carried-onto-the-events
  (testing "message_metadata has something to hold — provenance is attached by
            the shell, so cart.core stays free of it"
    (let [d (deps)
          [_ data] (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))]
      (is (= [{:now now}] (mapv :metadata (:events data))))
      (is (= [{:now now}]
             (mapv :metadata (:events (store/read-stream (:event-store d)
                                                         "shopping_cart-c1"))))))))

(deftest core-events-are-not-given-metadata-by-decide
  (testing "SPEC R1.3 — decide stays pure and metadata-free; the stamp happens
            in the shell"
    (let [[_ events] (core/decide (add-cmd "shoes" 3) core/initial-state)]
      (is (not-any? #(contains? % :metadata) events)))))

;; ---------------------------------------------------------------------------
;; Interrupting the retry backoff
;; ---------------------------------------------------------------------------

(deftest an-interrupt-during-backoff-stops-retrying
  (testing "the interrupt must not escape as an exception, and must not be
            swallowed either — the caller's cancellation has to survive"
    (let [[es appends] (flaky-store (mem/make-store) 999)
          d {:event-store es :retry {:retries 3 :min-timeout 5000}}
          outcome (promise)
          flagged (promise)
          t (Thread. #(do (deliver outcome
                                   (try (first (handle/handle-command
                                                d "shopping_cart-c1" (add-cmd "shoes" 3)))
                                        (catch Throwable e [:threw e])))
                          (deliver flagged (.isInterrupted (Thread/currentThread)))))]
      (.start t)
      ;; let it lose once and enter the 5s backoff, then cancel it
      (Thread/sleep 300)
      (.interrupt t)
      (.join t 5000)

      (is (= :conflict @outcome)
          "the conflict it already had is a real answer, not an exception")
      (is (true? @flagged) "the interrupt flag must be restored, not cleared")
      (is (= 1 @appends) "it stopped rather than burning the rest of the budget")
      (is (not (.isAlive t))))))

(deftest an-empty-decision-never-reaches-the-store
  (testing "SPEC R4.7 — no events means no write at all, so no version bump
            and no possibility of conflict"
    (let [[es appends] (flaky-store (mem/make-store) 999)
          d {:event-store es}]
      ;; confirm on an empty cart is an :error, not an empty :ok, so drive R4.7
      ;; through a decide that legitimately yields nothing.
      (with-redefs [core/decide (fn [_ _] [:ok []])]
        (let [[outcome data] (handle/handle-command d "shopping_cart-c1" confirm-cmd)]
          (is (= :ok outcome))
          (is (= [] (:events data)))
          (is (= 0 (:version data)))
          (is (false? (:created-new-stream? data)))))
      (is (zero? @appends) "the store was never called"))))

(deftest state-is-rebuilt-from-events-each-time
  (let [d (deps)]
    (handle/handle-command d "shopping_cart-c1" (add-cmd "shoes" 3))
    (handle/handle-command d "shopping_cart-c1" (add-cmd "hat" 1))
    (let [[outcome _] (handle/handle-command d "shopping_cart-c1" confirm-cmd)]
      (is (= :ok outcome) "confirm needs the cart to be open, which only the events say"))))
