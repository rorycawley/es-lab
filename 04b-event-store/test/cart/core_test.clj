(ns cart.core-test
  "Pure tests. No fixtures, no Docker, no I/O (SPEC R6.1)."
  (:require [cart.core :as core]
            [cart.schema :as schema]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

(def now 1735689600000)

(defn- added [product-id quantity]
  {:type :cart.event/product-item-added
   :data {:cart-id "c1"
          :product-item {:product-id product-id :quantity quantity :unit-price 1999}
          :added-at now}})

(defn- removed [product-id quantity]
  {:type :cart.event/product-item-removed
   :data {:cart-id "c1"
          :product-item {:product-id product-id :quantity quantity :unit-price 1999}
          :removed-at now}})

(def confirmed
  {:type :cart.event/confirmed :data {:cart-id "c1" :confirmed-at now}})

;; ---------------------------------------------------------------------------
;; evolve / fold
;; ---------------------------------------------------------------------------

(deftest fold-builds-state-from-events
  (is (= {:status :empty} (core/fold [])))

  (is (= {:status :opened :product-items {"shoes" 3}}
         (core/fold [(added "shoes" 3)])))

  (is (= {:status :opened :product-items {"shoes" 2 "hat" 1}}
         (core/fold [(added "shoes" 3) (added "hat" 1) (removed "shoes" 1)]))))

(deftest removing-to-zero-drops-the-key
  (is (= {:status :opened :product-items {}}
         (core/fold [(added "shoes" 1) (removed "shoes" 1)]))))

(deftest confirming-slims-the-state
  (testing "a closed cart carries no product items — no rule consults them"
    (is (= {:status :closed}
           (core/fold [(added "shoes" 3) confirmed])))))

(deftest unknown-events-are-ignored-not-fatal
  (testing "SPEC R2.6 — an event from a newer deploy, read back after rollback"
    (let [events [(added "shoes" 3)
                  {:type :cart.event/gift-wrapped :data {}}]]
      (is (= {:status :opened :product-items {"shoes" 3}}
             (core/fold events))))))

(deftest closed-carts-ignore-further-events
  (is (= {:status :closed}
         (core/fold [(added "shoes" 3) confirmed (added "hat" 1)]))))

;; ---------------------------------------------------------------------------
;; decide
;; ---------------------------------------------------------------------------

(defn- add-cmd [product-id quantity]
  {:type :cart.command/add-product-item
   :data {:cart-id "c1"
          :product-item {:product-id product-id :quantity quantity :unit-price 1999}}
   :metadata {:now now}})

(defn- remove-cmd [product-id quantity]
  {:type :cart.command/remove-product-item
   :data {:cart-id "c1"
          :product-item {:product-id product-id :quantity quantity :unit-price 1999}}
   :metadata {:now now}})

(def confirm-cmd
  {:type :cart.command/confirm :data {:cart-id "c1"} :metadata {:now now}})

(def cancel-cmd
  {:type :cart.command/cancel :data {:cart-id "c1"} :metadata {:now now}})

(deftest adding-to-an-empty-cart-succeeds
  (let [[outcome events] (core/decide (add-cmd "shoes" 3) core/initial-state)]
    (is (= :ok outcome))
    (is (= 1 (count events)))
    (is (= :cart.event/product-item-added (:type (first events))))
    (is (= now (get-in (first events) [:data :added-at]))
        "timestamp comes from command metadata, not a clock")
    (is (not-any? #(contains? % :metadata) events)
        "request metadata is added by the application shell, not by cart.core")))

(deftest adding-to-a-closed-cart-is-an-error
  (let [state (core/fold [(added "shoes" 3) confirmed])]
    (is (= [:error {:reason :cart-closed}]
           (core/decide (add-cmd "hat" 1) state)))))

(deftest removing-from-an-open-cart-succeeds
  (let [state (core/fold [(added "shoes" 3)])
        [outcome events] (core/decide (remove-cmd "shoes" 1) state)]
    (is (= :ok outcome))
    (is (= [{:type :cart.event/product-item-removed
             :data {:cart-id "c1"
                    :product-item {:product-id "shoes"
                                   :quantity 1
                                   :unit-price 1999}
                    :removed-at now}}]
           events))))

(deftest removing-more-than-held-is-an-error
  (let [state (core/fold [(added "shoes" 2)])
        cmd   (remove-cmd "shoes" 5)]
    (is (= [:error {:reason :insufficient-quantity}]
           (core/decide cmd state)))))

(deftest removing-from-a-closed-cart-is-an-error
  (let [state (core/fold [(added "shoes" 3) confirmed])]
    (is (= [:error {:reason :cart-closed}]
           (core/decide (remove-cmd "shoes" 1) state)))))

(deftest confirming-an-empty-cart-is-an-error
  (is (= [:error {:reason :not-opened}]
         (core/decide confirm-cmd core/initial-state))))

(deftest confirming-an-open-cart-with-no-items-is-an-error
  (testing "aggregate invariant: a cart with no held items cannot be confirmed"
    (let [state (core/fold [(added "shoes" 1) (removed "shoes" 1)])]
      (is (= [:error {:reason :empty-cart}]
             (core/decide confirm-cmd state))))))

(deftest confirming-an-already-closed-cart-is-an-error
  (let [state (core/fold [(added "shoes" 3) confirmed])]
    (is (= [:error {:reason :not-opened}]
           (core/decide confirm-cmd state)))))

(deftest confirming-an-open-cart-succeeds
  (let [state (core/fold [(added "shoes" 3)])
        [outcome events] (core/decide confirm-cmd state)]
    (is (= :ok outcome))
    (is (= :cart.event/confirmed (:type (first events))))))

(deftest cancelling-an-empty-cart-succeeds
  (let [[outcome events] (core/decide cancel-cmd core/initial-state)]
    (is (= :ok outcome))
    (is (= [{:type :cart.event/cancelled
             :data {:cart-id "c1" :cancelled-at now}}]
           events))))

(deftest cancelling-an-open-cart-succeeds
  (let [state (core/fold [(added "shoes" 3)])
        [outcome events] (core/decide cancel-cmd state)]
    (is (= :ok outcome))
    (is (= [{:type :cart.event/cancelled
             :data {:cart-id "c1" :cancelled-at now}}]
           events))))

(deftest cancelling-an-already-closed-cart-is-an-error
  (let [state (core/fold [(added "shoes" 3) confirmed])]
    (is (= [:error {:reason :already-closed}]
           (core/decide cancel-cmd state)))))

(deftest decide-is-pure
  (testing "same inputs, same outputs, no clock"
    (let [state (core/fold [(added "shoes" 3)])]
      (is (= (core/decide confirm-cmd state)
             (core/decide confirm-cmd state))))))

;; ---------------------------------------------------------------------------
;; Exhaustiveness (SPEC R6.3)
;; ---------------------------------------------------------------------------

(deftest every-command-in-the-schema-has-a-decide-method
  (testing "stands in for TypeScript's `never` check"
    (let [declared (set (map first (m/children (m/schema schema/Command))))
          handled  (set (keys (methods core/decide)))]
      (is (= declared handled)
          (str "unhandled: " (set/difference declared handled)
               " / undeclared: " (set/difference handled declared))))))

(deftest every-event-in-the-schema-has-an-evolve-method
  (let [declared (set (map first (m/children (m/schema schema/Event))))
        handled  (disj (set (keys (methods core/evolve))) :default)]
    (is (= declared handled))))

(deftest decide-has-no-default-method
  (testing "SPEC R2.6 — an unknown command is a bug, and should be loud"
    (is (nil? (get (methods core/decide) :default)))))

(deftest evolve-has-a-default-method
  (testing "SPEC R2.6 — an unknown event is a rollback, and must be survivable"
    (is (some? (get (methods core/evolve) :default)))))

;; ---------------------------------------------------------------------------
;; State always matches its schema
;; ---------------------------------------------------------------------------

(deftest folded-state-always-validates
  (doseq [events [[]
                  [(added "shoes" 3)]
                  [(added "shoes" 3) (removed "shoes" 3)]
                  [(added "shoes" 3) confirmed]]]
    (is (m/validate schema/ShoppingCart (core/fold events))
        (str "failed for " (mapv :type events)))))
