(ns lab3.message-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab3.message :as message]))

(deftest flavour-sold-shape-test
  (testing "each example has the flavour-sold message type"
    (doseq [example message/examples]
      (is (= :flavour-sold (:message/type example))))))

(deftest flavour-sold-payload-test
  (testing "each example names a flavour in its payload, as a string"
    ;; This repository's later wire format is JSON, so the contract uses its
    ;; portable representation here.
    (doseq [example message/examples]
      (is (string? (get-in example [:payload :flavour]))))))

(deftest flavour-sold-vanilla-integration-message-test
  (is (= {:message/type :flavour-sold
          :payload      {:flavour "vanilla"}}
         message/flavour-sold-vanilla-integration-message)))

(deftest examples-are-distinct-test
  (is (= (count message/examples)
         (count (distinct message/examples)))))

(deftest the-delivery-and-the-fact-have-different-identities-test
  (let [m message/flavour-sold-vanilla-message]
    (testing "the envelope identifies this send"
      (is (uuid? (:message/id m))))
    (testing "the payload identifies the fact for the receiving module"
      (is (string? (get-in m [:payload :fact-id])))
      (is (uuid? (parse-uuid (get-in m [:payload :fact-id]))))
      (is (nil? (get-in m [:payload :event/id]))
          "a wire contract does not expose a Clojure-namespaced key"))
    (testing "republishing changes the delivery identity, not the fact identity"
      (let [republished (assoc m :message/id
                               #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d38")]
        (is (not= (:message/id m) (:message/id republished)))
        (is (= (get-in m [:payload :fact-id])
               (get-in republished [:payload :fact-id])))))))

(deftest correlation-and-causation-describe-the-chain-test
  (let [metadata (:metadata message/flavour-sold-vanilla-message)]
    (is (= #{:correlation-id :causation-id} (set (keys metadata))))
    (is (uuid? (:correlation-id metadata)))
    (is (uuid? (:causation-id metadata)))))
