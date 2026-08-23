(ns lab33.rules-test
  "The closed check, which is where 'values, not structure' stops being advice."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.fixture :as fixture]
            [lab33.rules :as rules]))

(deftest a-declared-parameter-with-the-right-shape-is-accepted-test
  (is (rules/valid? {:reporting-threshold 15000M}))
  (is (= 15000M (-> (rules/configure {:reporting-threshold 15000M})
                    (rules/parameter :reporting-threshold))))
  (testing "and the rest of the defaults come along"
    (is (= 0M (rules/parameter (rules/configure {}) :overdraft-limit)))))

(deftest an-undeclared-key-is-refused-test
  ;; The friction is the feature. Adding a parameter is an edit to `parameters`
  ;; with a code review attached, rather than a key somebody put in a file.
  (is (= [":flag-when is not a configurable parameter"]
         (rules/problems {:flag-when true})))
  (is (= :not-configuration (fixture/reason #(rules/configure {:flag-when true})))))

(deftest a-rule-expressed-as-structure-is-refused-test
  ;; The whole point of the closed check. `engine/predicate.clj` can interpret
  ;; this vector perfectly well; what it cannot do is arrive here as
  ;; configuration.
  (let [as-data [:and [:> :amount 10000M] [:= :direction "debit"]]]
    (testing "not under a declared key"
      (is (= [":reporting-threshold must satisfy decimal?, got [:and [:> :amount 10000M] [:= :direction \"debit\"]]"]
             (rules/problems {:reporting-threshold as-data}))))
    (testing "and not under a new one"
      (is (= [":flag-when is not a configurable parameter"]
             (rules/problems {:flag-when as-data}))))))

(deftest every-parameter-is-a-scalar-test
  ;; A structural guard on the guard: if a predicate here ever accepted a
  ;; collection, the closed check would stop closing anything.
  (doseq [[parameter pred] rules/parameters]
    (testing (str parameter)
      (is (not (pred [])) "accepts a vector")
      (is (not (pred {})) "accepts a map")
      (is (not (pred #{})) "accepts a set"))))

(deftest a-wrong-type-says-what-was-expected-test
  ;; Vars rather than function values, so the refusal can name the predicate.
  (is (= [":overdraft-limit must satisfy decimal?, got 500"]
         (rules/problems {:overdraft-limit 500}))
      "an integer is not money — lab 32's gotcha, still true here"))

(deftest problems-are-ordered-so-the-message-is-stable-test
  ;; A refusal that lists its complaints in hash order is a refusal nobody can
  ;; write a test against, including this one.
  (let [config {:zebra true :alpha true}]
    (is (= (rules/problems config) (rules/problems config)))
    (is (= [":alpha is not a configurable parameter"
            ":zebra is not a configurable parameter"]
           (rules/problems config)))))

(deftest configuration-must-be-a-map-test
  (is (= ["configuration must be a map"] (rules/problems [:reporting-threshold 15000M]))))

(deftest reading-an-undeclared-parameter-is-refused-test
  ;; The typo that would otherwise return nil and be arithmetic on nothing.
  (is (= :unknown-parameter
         (fixture/reason #(rules/parameter (rules/configure {}) :reporting-threshhold)))))

(deftest merging-is-flat-test
  ;; Deep-merging configuration is how nesting — which is to say structure —
  ;; arrives without anybody deciding to allow it.
  (is (= :not-configuration
         (fixture/reason #(rules/configure {:reporting-threshold {:default 10000M}})))))
