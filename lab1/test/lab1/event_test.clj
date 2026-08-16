(ns lab1.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab1.event :as event]))

(deftest flavour-sold-shape-test
  (testing "each example has the flavour-sold event type"
    (doseq [example event/examples]
      (is (= :flavour-sold (:event/type example))))))

(deftest flavour-sold-flavour-test
  (testing "each example names a flavour"
    (doseq [example event/examples]
      (is (keyword? (:flavour example))))))

(deftest flavour-sold-vanilla-test
  (is (= {:event/type :flavour-sold
          :flavour    :vanilla}
         event/flavour-sold-vanilla)))

(deftest flavour-sold-vanilla-envelope-test
  (testing "the envelope carries type, with the flavour in the data"
    (is (= {:event/type :flavour-sold
            :data       {:flavour :vanilla}}
           event/flavour-sold-vanilla-envelope))
    (is (keyword? (get-in event/flavour-sold-vanilla-envelope [:data :flavour])))))

(deftest examples-are-distinct-test
  (is (= (count event/examples)
         (count (distinct event/examples)))))
