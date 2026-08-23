(ns lab32.domain-test
  "The pure half. No fixture, no container, no clock.

  That this file needs nothing running is the assertion, not a convenience.
  Lab 0's criterion applied to lab 32: the business rules are the part that
  must be readable and checkable on their own, and a rule that needs a database
  to exercise is a rule that has been mixed with something else."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.domain :as domain]
            [lab32.money :as money]))

(def account #uuid "0f1c2b3a-0000-4000-8000-000000000032")

(defn- opened []
  [{:event/type :accounts/account-opened :data {:account-id account :holder "Ada"}}])

(defn- deposited [amount]
  {:event/type :accounts/money-deposited :data {:account-id account :amount (money/of amount)}})

(deftest an-absent-account-has-no-balance-test
  (is (= :absent (:status domain/initial-state)))
  (is (zero? (:balance domain/initial-state))))

(deftest the-fold-derives-the-balance-test
  (let [state (domain/replay (concat (opened) [(deposited 100) (deposited 250)]))]
    (is (= :open (:status state)))
    (is (= "Ada" (:holder state)))
    (is (zero? (compare (money/of 350) (:balance state))))))

(deftest the-fold-ignores-event-types-it-does-not-know-test
  ;; A fold that throws on an unfamiliar type cannot survive its own history:
  ;; the moment an old event is renamed or a new one added, every aggregate
  ;; written before the change becomes unreadable.
  (let [state (domain/replay (concat (opened)
                                     [{:event/type :accounts/something-invented-later
                                       :data       {}}]
                                     [(deposited 10)]))]
    (is (zero? (compare (money/of 10) (:balance state))))))

(deftest opening-twice-is-refused-test
  (let [state (domain/replay (opened))]
    (is (= :account-already-open
           (:reason (ex-data (try (domain/decide state {:command/type :accounts/open-account
                                                        :data {:account-id account
                                                               :holder "Ada"}})
                                  (catch clojure.lang.ExceptionInfo e e))))))))

(deftest moving-money-on-an-account-that-is-not-open-is-refused-test
  (doseq [command [:accounts/deposit :accounts/withdraw]]
    (is (= :account-not-open
           (:reason (ex-data (try (domain/decide domain/initial-state
                                                 {:command/type command
                                                  :data {:account-id account
                                                         :amount (money/of 5)}})
                                  (catch clojure.lang.ExceptionInfo e e))))))))

(deftest the-invariant-holds-against-the-folded-balance-test
  (testing "a withdrawal within the balance is allowed"
    (let [state (domain/replay (concat (opened) [(deposited 100)]))
          [event] (domain/decide state {:command/type :accounts/withdraw
                                        :data {:account-id account :amount (money/of 100)}})]
      (is (= :accounts/money-withdrawn (:event/type event)))))
  (testing "a withdrawal beyond it is not"
    (let [state (domain/replay (concat (opened) [(deposited 100)]))
          thrown (try (domain/decide state {:command/type :accounts/withdraw
                                            :data {:account-id account
                                                   :amount (money/of 100.01M)}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :insufficient-funds (:reason (ex-data thrown))))
      (is (zero? (compare (money/of 100) (:balance (ex-data thrown))))))))

(deftest an-amount-must-be-a-positive-decimal-test
  (doseq [[amount reason] [[(money/of 0) :amount-not-positive]
                           [(money/of -1) :amount-not-positive]
                           [100 :amount-not-decimal]]]
    (is (= reason
           (:reason (ex-data (try (domain/decide (domain/replay (opened))
                                                 {:command/type :accounts/deposit
                                                  :data {:account-id account :amount amount}})
                                  (catch clojure.lang.ExceptionInfo e e)))))
        (str amount " should be refused as " reason))))

(deftest a-decision-returns-a-collection-test
  ;; Lab 5's cardinality, kept visible even though every command here happens
  ;; to produce exactly one event.
  (is (sequential? (domain/decide domain/initial-state
                                  {:command/type :accounts/open-account
                                   :data {:account-id account :holder "Ada"}}))))
