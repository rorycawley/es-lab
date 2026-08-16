(ns lab3.message-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab3.message :as message]))

(deftest flavour-sold-shape-test
  (testing "each example has the flavour-sold message type"
    (doseq [example message/examples]
      (is (= :flavour-sold (:message/type example))))))

(deftest flavour-sold-payload-test
  (testing "each example names a flavour in its payload"
    (doseq [example message/examples]
      (is (keyword? (get-in example [:payload :flavour]))))))

(deftest flavour-sold-vanilla-integration-message-test
  (is (= {:message/type :flavour-sold
          :payload      {:flavour :vanilla}}
         message/flavour-sold-vanilla-integration-message)))

(deftest examples-are-distinct-test
  (is (= (count message/examples)
         (count (distinct message/examples)))))
