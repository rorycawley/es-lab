(ns lab10.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab10.policy :as policy]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def depletion-1-id #uuid "018f7a3e-0000-7000-8000-000000001001")
(def depletion-2-id #uuid "018f7a3e-0000-7000-8000-000000001002")
(def sold-id #uuid "018f7a3e-0000-7000-8000-000000001003")
(def loaded-id #uuid "018f7a3e-0000-7000-8000-000000001004")
(def repainted-id #uuid "018f7a3e-0000-7000-8000-000000001005")
(def unknown-id #uuid "018f7a3e-0000-7000-8000-000000001006")

(defn- depleted
  [event-id]
  {:event/id   event-id
   :event/type :stock-depleted
   :stream/id  truck-1
   :data       {:flavour "vanilla"}})

(deftest a-policy-turns-a-fact-into-a-request-test
  (let [[command :as commands] (policy/react (depleted depletion-1-id))]
    (is (= 1 (count commands)))
    (is (= :load-truck (:command/type command)))
    (is (= truck-1 (get-in command [:data :truck-id])) "addressed to that truck")
    (is (= "vanilla" (get-in command [:data :flavour])))))

(deftest a-policy-explicitly-ignores-known-irrelevant-events-test
  (is (= [] (policy/react {:event/id sold-id :event/type :flavour-sold})))
  (is (= [] (policy/react {:event/id loaded-id :event/type :truck-loaded})))
  (is (= [] (policy/react {:event/id repainted-id :event/type :truck-repainted}))))

(deftest a-policy-rejects-unknown-event-semantics-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (policy/react {:event/id unknown-id
                                       :event/type :freezer-failed}))))

(deftest a-policy-is-a-function-of-the-event-alone-test
  (testing "no store, no state, no clock — same event, same commands"
    (let [event (depleted depletion-1-id)]
      (is (= (policy/react event) (policy/react event))))))

(deftest the-command-id-is-derived-from-the-event-test
  (testing "a redelivered event produces the identical command"
    (let [event (depleted depletion-1-id)]
      (is (= (:command/id (first (policy/react event)))
             (:command/id (first (policy/react event)))))))
  (testing "and different events produce different commands"
    (is (not= (:command/id (first (policy/react (depleted depletion-1-id))))
              (:command/id (first (policy/react (depleted depletion-2-id))))))))

(deftest two-policies-do-not-collide-on-one-event-test
  (testing "the policy's name is part of the derivation"
    (let [event (depleted depletion-1-id)]
      (is (not= (policy/derived-command-id :restock-when-depleted event)
                (policy/derived-command-id :notify-owner-when-depleted event))))))

(deftest react-to-all-counts-like-lab5-test
  (let [events [(depleted depletion-1-id)
                {:event/id sold-id :event/type :flavour-sold}
                (depleted depletion-2-id)]]
    (is (= 2 (count (policy/react-to-all events))))
    (is (every? #(= :load-truck (:command/type %)) (policy/react-to-all events)))))

(deftest a-derived-command-requires-a-recorded-event-id-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (policy/react {:event/type :stock-depleted
                                       :stream/id  truck-1
                                       :data       {:flavour "vanilla"}}))))
