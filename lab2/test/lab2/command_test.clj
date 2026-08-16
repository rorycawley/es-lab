(ns lab2.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab2.command :as command]))

(deftest buy-flavour-shape-test
  (testing "each example has the buy-flavour command type"
    (doseq [example command/examples]
      (is (= :buy-flavour (:command/type example))))))

(deftest buy-flavour-data-test
  (testing "each example names a flavour in its data"
    (doseq [example command/examples]
      (is (keyword? (get-in example [:data :flavour]))))))

(deftest buy-flavour-vanilla-command-test
  (is (= {:command/type :buy-flavour
          :data         {:flavour :vanilla}}
         command/buy-flavour-vanilla-command)))

(deftest examples-are-distinct-test
  (is (= (count command/examples)
         (count (distinct command/examples)))))
