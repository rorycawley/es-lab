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
  {:command/id (random-uuid) :command/type type :command/actor actor :data data})
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
  (let [[restock] (policy/react {:event/id event-1 :event/type :stock-depleted
                                 :stream/id truck-1 :data {:flavour "vanilla"}})]
    (is (= {:type "system" :id "restock-when-depleted"} (:command/actor restock)))
    (is (= {:truck-id truck-1 :flavour "vanilla" :quantity 20} (:data restock)))))
(deftest contract-mapping-is-pure-and-directly-testable-test
  (let [messages (contract/announce {:event/id event-1 :event/type :stock-depleted
                                     :stream/id truck-1 :data {:flavour "vanilla"}})]
    (is (= #{:customer-app :purchasing} (set (map :recipient messages))))
    (is (= "customer-app ← flavour-unavailable (vanilla)" (contract/describe (first messages))))))
