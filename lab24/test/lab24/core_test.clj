(ns lab24.core-test
  "Pure business rules tested directly, including stateful invariants."
  (:require [clojure.test :refer [deftest is]]
            [lab24.core.contract :as contract]
            [lab24.core.policy :as policy]
            [lab24.core.truck :as truck]))
(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def event-1 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def dana {:type "user" :id "USR-83721"})
(def sam {:type "user" :id "USR-44308"})
(defn- command [actor type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :command/actor actor :data data})
(deftest selling-the-final-cone-directly-specifies-the-invariant-test
  (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}
          {:event/type :stock-depleted :data {:flavour "vanilla"}}]
         (truck/decide (command dana :buy-flavour {:flavour "vanilla"})
                       {:stock {"vanilla" 1} :driver "USR-83721"}))))
(deftest ownership-is-checked-inside-the-pure-core-test
  (let [state {:stock {"vanilla" 1} :driver "USR-83721"}
        failure (try
                  (truck/decide (command sam :buy-flavour {:flavour "vanilla"}) state)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= :not-authorised (:reason (ex-data failure))))
    (is (= {:stock {"vanilla" 1} :driver "USR-83721"} state)
        "a pure refusal cannot mutate the supplied state")))
(deftest replay-derives-stock-and-driver-from-facts-test
  (is (= {:stock {"vanilla" 2} :driver "USR-83721"}
         (truck/replay [{:event/type :driver-assigned :data {:driver-id "USR-83721"}}
                        {:event/type :truck-loaded :data {:flavour "vanilla" :quantity 3}}
                        {:event/type :flavour-sold :data {:flavour "vanilla"}}]))))
(deftest depletion-policy-stamps-system-authority-test
  (let [correlation-id (random-uuid)
        [restock] (policy/react {:event/id event-1 :event/type :stock-depleted
                                 :stream/id truck-1
                                 :metadata {:correlation-id correlation-id}
                                 :data {:flavour "vanilla"}})]
    (is (= {:type "system" :id "restock-when-depleted"} (:command/actor restock)))
    (is (= correlation-id (:correlation-id restock)))
    (is (= {:truck-id truck-1 :flavour "vanilla" :quantity 20} (:data restock)))))

(deftest ensuring-stock-can-be-a-valid-zero-fact-outcome-test
  (is (= [] (truck/decide (command dana :ensure-stock
                                   {:flavour "vanilla" :quantity 2})
                          {:stock {"vanilla" 3} :driver nil})))
  (is (= [{:event/type :truck-loaded
           :data {:flavour "vanilla" :quantity 2}}]
         (truck/decide (command dana :ensure-stock
                                {:flavour "vanilla" :quantity 3})
                       {:stock {"vanilla" 1} :driver nil}))))

(deftest unknown-event-and-command-semantics-fail-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                        (truck/decide (command dana :steal-truck {}) truck/initial-state))))
(deftest contract-mapping-is-pure-and-directly-testable-test
  (let [messages (contract/announce {:event/id event-1 :event/type :stock-depleted
                                     :stream/id truck-1 :data {:flavour "vanilla"}})]
    (is (= #{:customer-app :purchasing} (set (map :recipient messages))))
    (is (= "customer-app ← flavour-unavailable (vanilla)" (contract/describe (first messages))))))

(deftest integration-contract-event-semantics-are-explicit-test
  (is (= [] (contract/announce {:event/type :truck-loaded})))
  (is (= [] (contract/announce {:event/type :driver-assigned})))
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
