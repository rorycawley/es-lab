(ns lab23.core-test
  "Business rules tested directly as pure functions of values.

  No system, adapter, fake, mock or fixture is required. These tests specify
  domain inputs and outputs, not internal interaction choreography."
  (:require [clojure.test :refer [deftest is]]
            [lab23.core.contract :as contract]
            [lab23.core.policy :as policy]
            [lab23.core.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def event-1 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(deftest selling-the-final-cone-directly-specifies-the-invariant-test
  (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}
          {:event/type :stock-depleted :data {:flavour "vanilla"}}]
         (truck/decide (command :buy-flavour {:flavour "vanilla"})
                       {"vanilla" 1}))))
(deftest sold-out-is-a-pure-domain-refusal-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                        (truck/decide (command :buy-flavour {:flavour "vanilla"}) {}))))

(deftest positive-load-quantity-remains-a-domain-invariant-test
  (doseq [quantity [0 -1 1.5 nil]]
    (is (= :invalid-quantity
           (:reason (ex-data
                     (try (truck/decide
                           (command :load-truck {:flavour "vanilla"
                                                 :quantity quantity}) {})
                          (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ensure-stock-can-be-a-valid-no-op-test
  (is (= [] (truck/decide
             (command :ensure-stock {:flavour "vanilla" :quantity 3})
             {"vanilla" 3}))))
(deftest replay-derives-current-stock-from-facts-test
  (is (= {"vanilla" 2}
         (truck/replay [{:event/type :truck-loaded :data {:flavour "vanilla" :quantity 3}}
                        {:event/type :flavour-sold :data {:flavour "vanilla"}}]))))

(deftest known-irrelevant-and-unknown-history-are-distinct-test
  (is (= {} (truck/replay [{:event/type :stock-depleted
                            :data {:flavour "vanilla"}}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed :data {}}]))))
(deftest depletion-policy-directly-produces-a-restock-request-test
  (let [[restock] (policy/react {:event/id event-1 :event/type :stock-depleted
                                 :stream/id truck-1 :data {:flavour "vanilla"}})]
    (is (= :load-truck (:command/type restock)))
    (is (= {:truck-id truck-1 :flavour "vanilla" :quantity 20} (:data restock)))))
(deftest contract-mapping-is-pure-and-directly-testable-test
  (let [messages (contract/announce {:event/id event-1 :event/type :stock-depleted
                                     :stream/id truck-1 :data {:flavour "vanilla"}})]
    (is (= #{:customer-app :purchasing} (set (map :recipient messages))))
    (is (= "customer-app ← flavour-unavailable (vanilla)"
           (contract/describe (first messages))))))

(deftest integration-contract-event-semantics-are-explicit-test
  (is (= [] (contract/announce {:event/type :truck-loaded})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (contract/announce {:event/type :freezer-failed}))))

(deftest policy-event-semantics-are-explicit-test
  (is (= [] (policy/react {:event/id event-1
                           :event/type :truck-loaded})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (policy/react {:event/id event-1
                                       :event/type :freezer-failed}))))

(deftest policy-requires-the-triggering-facts-identity-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (policy/react {:event/type :stock-depleted
                                       :stream/id truck-1
                                       :data {:flavour "vanilla"}}))))
