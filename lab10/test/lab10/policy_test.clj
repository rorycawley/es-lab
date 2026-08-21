(ns lab10.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab10.policy :as policy]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- depleted
  ([] (depleted (random-uuid)))
  ([event-id]
   {:event/id   event-id
    :event/type :stock-depleted
    :stream/id  truck-1
    :data       {:flavour "vanilla"}}))

(deftest a-policy-turns-a-fact-into-a-request-test
  (let [[command :as commands] (policy/react (depleted))]
    (is (= 1 (count commands)))
    (is (= :load-truck (:command/type command)))
    (is (= truck-1 (get-in command [:data :truck-id])) "addressed to that truck")
    (is (= "vanilla" (get-in command [:data :flavour])))))

(deftest a-policy-has-no-opinion-about-most-events-test
  (testing "the same shrug a fold gives (lab 6)"
    (is (= [] (policy/react {:event/id (random-uuid) :event/type :flavour-sold})))
    (is (= [] (policy/react {:event/id (random-uuid) :event/type :truck-loaded})))
    (is (= [] (policy/react {:event/id (random-uuid) :event/type :truck-repainted})))))

(deftest a-policy-is-a-function-of-the-event-alone-test
  (testing "no store, no state, no clock — same event, same commands"
    (let [event (depleted)]
      (is (= (policy/react event) (policy/react event))))))

(deftest the-command-id-is-derived-from-the-event-test
  (testing "a redelivered event produces the identical command"
    (let [event (depleted)]
      (is (= (:command/id (first (policy/react event)))
             (:command/id (first (policy/react event)))))))
  (testing "and different events produce different commands"
    (is (not= (:command/id (first (policy/react (depleted))))
              (:command/id (first (policy/react (depleted))))))))

(deftest two-policies-do-not-collide-on-one-event-test
  (testing "the policy's name is part of the derivation"
    (let [event (depleted)]
      (is (not= (policy/derived-command-id :restock-when-depleted event)
                (policy/derived-command-id :notify-owner-when-depleted event))))))

(deftest react-to-all-counts-like-lab5-test
  (let [events [(depleted) {:event/id (random-uuid) :event/type :flavour-sold} (depleted)]]
    (is (= 2 (count (policy/react-to-all events))))
    (is (every? #(= :load-truck (:command/type %)) (policy/react-to-all events)))))
