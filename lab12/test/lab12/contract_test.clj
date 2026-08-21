(ns lab12.contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab12.contract :as contract]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- depleted []
  {:event/id   (random-uuid)
   :event/type :stock-depleted
   :stream/id  truck-1
   :data       {:flavour "vanilla"}})

(deftest most-facts-are-published-to-nobody-test
  (testing "the default is silence (lab 5)"
    (is (= [] (contract/announce {:event/type :flavour-sold :data {:flavour "vanilla"}})))
    (is (= [] (contract/announce {:event/type :truck-loaded :data {:flavour "vanilla"}})))
    (is (= [] (contract/announce {:event/type :truck-repainted})))))

(deftest one-fact-can-become-several-messages-test
  (testing "two audiences, two contracts"
    (let [messages (contract/announce (depleted))]
      (is (= 2 (count messages)))
      (is (= [:flavour-unavailable :restock-required]
             (map :message/type messages))))))

(deftest every-message-carries-the-fact-inside-the-payload-test
  (let [event    (depleted)
        messages (contract/announce event)]
    (doseq [message messages]
      (is (= (:event/id event) (get-in message [:payload :event/id]))
          "the fact's identity travels as data")
      (is (= "vanilla" (get-in message [:payload :flavour]))))))

(deftest a-contract-carries-no-delivery-identity-test
  (testing ":message/id identifies a send, so the contract cannot know it yet"
    (doseq [message (contract/announce (depleted))]
      (is (nil? (:message/id message)))
      (is (= #{:message/type :payload} (set (keys message)))))))

(deftest the-contract-exposes-only-what-consumers-need-test
  (testing "no :data, no :stream/version, no metadata — the domain shape stays private"
    (let [payload (:payload (first (contract/announce (depleted))))]
      (is (= #{:event/id :truck-id :flavour} (set (keys payload)))))))

(deftest announce-all-preserves-order-test
  (let [a (depleted)
        b (depleted)
        messages (contract/announce-all [a {:event/type :flavour-sold} b])]
    (is (= 4 (count messages)))
    (is (= [(:event/id a) (:event/id a) (:event/id b) (:event/id b)]
           (map #(get-in % [:payload :event/id]) messages)))))
