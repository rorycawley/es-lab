(ns lab12.contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab12.contract :as contract]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def event-1 #uuid "018f7a3e-0000-7000-8000-000000001201")
(def event-2 #uuid "018f7a3e-0000-7000-8000-000000001202")

(defn- depleted [event-id]
  {:event/id   event-id
   :event/type :stock-depleted
   :stream/id  truck-1
   :data       {:flavour "vanilla"}})

(deftest known-private-facts-are-explicitly-published-to-nobody-test
  (is (= [] (contract/announce {:event/type :flavour-sold
                                :data {:flavour "vanilla"}})))
  (is (= [] (contract/announce {:event/type :truck-loaded
                                :data {:flavour "vanilla"}}))))

(deftest unknown-event-semantics-stop-the-relay-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (contract/announce {:event/type :truck-repainted}))))

(deftest one-fact-can-become-several-addressed-messages-test
  (testing "two audiences, two contracts"
    (let [messages (contract/announce (depleted event-1))]
      (is (= 2 (count messages)))
      (is (= [:flavour-unavailable :restock-required]
             (map :message/type messages)))
      (is (= [:customer-app :purchasing]
             (map :recipient messages))))))

(deftest every-message-carries-the-fact-inside-the-payload-test
  (let [event    (depleted event-1)
        messages (contract/announce event)]
    (doseq [message messages]
      (is (= (:event/id event) (get-in message [:payload :event/id])))
      (is (= "vanilla" (get-in message [:payload :flavour]))))))

(deftest a-contract-carries-no-envelope-identity-test
  (testing ":message/id belongs to the new envelope created by the relay"
    (doseq [message (contract/announce (depleted event-1))]
      (is (nil? (:message/id message)))
      (is (= #{:message/type :recipient :payload} (set (keys message)))))))

(deftest the-contract-exposes-only-what-consumers-need-test
  (let [payload (:payload (first (contract/announce (depleted event-1))))]
    (is (= #{:event/id :truck-id :flavour} (set (keys payload))))))

(deftest an-announced-fact-must-have-a-valid-identity-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (contract/announce (dissoc (depleted event-1) :event/id)))))

(deftest announce-all-preserves-input-and-contract-order-test
  (let [a (depleted event-1)
        b (depleted event-2)
        messages (contract/announce-all [a {:event/type :flavour-sold} b])]
    (is (= 4 (count messages)))
    (is (= [event-1 event-1 event-2 event-2]
           (map #(get-in % [:payload :event/id]) messages)))))
