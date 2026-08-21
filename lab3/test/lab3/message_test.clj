(ns lab3.message-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab3.message :as message]))

(deftest flavour-sold-shape-test
  (testing "each example has the flavour-sold message type"
    (doseq [example message/examples]
      (is (= :flavour-sold (:message/type example))))))

(deftest flavour-sold-payload-test
  (testing "each example names a flavour in its payload, as a string"
    ;; A message crosses a boundary by definition, so this one is not even a
    ;; judgement call: whatever is in here has to be expressible in JSON.
    (doseq [example message/examples]
      (is (string? (get-in example [:payload :flavour]))))))

(deftest flavour-sold-vanilla-integration-message-test
  (is (= {:message/type :flavour-sold
          :payload      {:flavour "vanilla"}}
         message/flavour-sold-vanilla-integration-message)))

(deftest examples-are-distinct-test
  (is (= (count message/examples)
         (count (distinct message/examples)))))
