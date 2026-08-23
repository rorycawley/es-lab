(ns lab33.predicate-test
  "The rules engine, and the reason not to build one.

  These tests pass. That is the point — the interpreter works, and every
  problem with it is a problem the tests below can state but a running system
  cannot."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.engine.predicate :as predicate]
            [lab33.fixture :as fixture]
            [lab33.rules :as rules]))

(def movement {:amount 12000M :direction "debit" :country "IE"})

(def reportable
  [:and [:> :amount 10000M] [:= :direction "debit"]])

(deftest it-works-which-is-the-problem-test
  (is (true? (predicate/evaluate reportable movement)))
  (is (false? (predicate/evaluate reportable (assoc movement :amount 500M))))
  (is (false? (predicate/evaluate reportable (assoc movement :direction "credit"))))
  (testing "and it reads back in something close to English, which is why it keeps being proposed"
    (is (= "(amount > 10000 and direction = \"debit\")" (predicate/explain reportable)))))

(deftest a-typo-is-a-valid-program-test
  ;; The failure this whole lab exists to avoid.
  ;;
  ;; `:ammount` is not a mistake any tool here can catch. There is no compiler,
  ;; because it is data. There is no test, because it lives in configuration.
  ;; What happens is that `(get fact :ammount)` is nil, the comparison is
  ;; false, and the rule matches nothing at all.
  (let [misspelled [:and [:> :ammount 10000M] [:= :direction "debit"]]]
    (is (false? (predicate/evaluate misspelled movement)))
    (is (false? (predicate/evaluate misspelled (assoc movement :amount 999999M))))
    (testing "so a compliance report comes back empty"
      (is (empty? (filter (partial predicate/evaluate misspelled)
                          [movement
                           (assoc movement :amount 50000M)
                           (assoc movement :amount 900000M)])))
      (testing "which is indistinguishable from a quiet month"
        (is (empty? (filter (partial predicate/evaluate reportable)
                            [(assoc movement :amount 10M)])))))))

(deftest the-loud-failures-are-the-ones-you-would-have-caught-anyway-test
  ;; Note which mistakes it does catch: structural ones. An unknown operator
  ;; and a malformed rule both throw. The mistake it cannot catch is the one
  ;; involving a *name*, and names are what business rules are made of.
  (is (= :unknown-operator (fixture/reason #(predicate/evaluate [:approximately :amount 1M] movement))))
  (is (= :malformed-rule (fixture/reason #(predicate/evaluate "amount > 10000" movement)))))

(deftest this-cannot-become-configuration-test
  ;; `rules.clj` is what keeps the interpreter out of the system. The rule
  ;; above is a perfectly good value and there is no key it can be the value
  ;; of.
  (is (false? (rules/valid? {:flag-when reportable})))
  (is (false? (rules/valid? {:reporting-threshold reportable})))
  (testing "the parameter it wanted to be is a number, and a number is checkable"
    (is (true? (rules/valid? {:reporting-threshold 10000M})))))
