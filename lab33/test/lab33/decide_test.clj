(ns lab33.decide-test
  "The conditional case: `decide` may read a parameter, and must record it.

  A decision is reproducible or it is not, and the difference is whether the
  event carries what the decision used. Lab 18 needs that to re-run a decision
  at all; this lab shows the specific way configuration destroys it."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.account :as account]
            [lab33.fixture :as fixture]
            [lab33.rules :as rules]))

(def funded {:status :open :balance 100M})

(defn- withdraw [amount fee limit]
  {:command/type :withdraw
   :data {:amount amount :withdrawal-fee fee :overdraft-limit limit}})

;; ---------------------------------------------------------------------------
;; The parameter arrives as an input
;; ---------------------------------------------------------------------------

(deftest the-limit-comes-from-the-command-not-a-registry-test
  (testing "an overdraft is permitted when the command carries a limit for it"
    (is (= [:money-withdrawn]
           (mapv :event/type (account/decide (withdraw 300M 0M 500M) funded)))))
  (testing "and refused when it does not"
    (is (= :insufficient-funds
           (fixture/reason #(account/decide (withdraw 300M 0M 0M) funded))))))

(deftest a-missing-parameter-is-refused-rather-than-defaulted-test
  ;; The alternative — `(or limit 0M)` — is worse than it looks. It turns a
  ;; wiring mistake into a business decision made on a number nobody chose,
  ;; and the customer is told their withdrawal was declined.
  (is (= :limit-not-supplied (fixture/reason #(account/decide (withdraw 10M 0M nil) funded))))
  (is (= :fee-not-supplied (fixture/reason #(account/decide (withdraw 10M nil 0M) funded)))))

;; ---------------------------------------------------------------------------
;; And is recorded, in the right half of the event
;; ---------------------------------------------------------------------------

(deftest the-fee-is-data-and-the-limit-is-metadata-test
  ;; REFERENCE.md's rule: would a domain expert recognise this as part of the
  ;; fact? The fee is money that left the account, so yes. The limit is why
  ;; the decision went the way it did, so no.
  (let [[event] (account/decide (withdraw 300M 2M 500M) funded)]
    (is (= {:amount 300M :fee 2M} (:data event)))
    (is (= {:rules {:overdraft-limit 500M}} (:metadata event)))))

(deftest a-stamped-decision-re-runs-identically-test
  ;; The property the stamp exists for. The configuration moves underneath and
  ;; the recorded decision is still reproducible, because everything it used
  ;; is on the event.
  (let [[recorded] (account/decide (withdraw 300M 2M 500M) funded)
        limit      (get-in recorded [:metadata :rules :overdraft-limit])
        fee        (get-in recorded [:data :fee])
        re-run     #(account/decide (withdraw 300M fee limit) funded)]
    (is (= [recorded] (re-run)))
    (testing "and again, under configuration that would have refused it"
      (let [tightened (rules/configure {:overdraft-limit 0M})]
        (is (= :insufficient-funds
               (fixture/reason #(account/decide
                                 (withdraw 300M fee (rules/parameter tightened :overdraft-limit))
                                 funded)))
            "today's limit refuses it — which is why today's limit is the wrong input")
        (is (= [recorded] (re-run))
            "the recorded one still permits it, which is the correct answer")))))

(deftest an-unstamped-decision-cannot-be-re-run-test
  ;; What the previous test would look like if the limit had not been kept.
  ;; There is no bug here to find — every function is correct. The information
  ;; required to get the right answer simply is not in the system.
  (let [in-force-then 500M
        in-force-now  0M
        outcome       (fn [limit] (fixture/reason #(account/decide (withdraw 300M 0M limit) funded)))]
    (is (= :no-refusal (outcome in-force-then)))
    (is (= :insufficient-funds (outcome in-force-now)))
    (is (not= (outcome in-force-then) (outcome in-force-now))
        "same command, same history, two verdicts — and nothing recorded which was right")))

;; ---------------------------------------------------------------------------
;; The fold, which reads neither
;; ---------------------------------------------------------------------------

(deftest the-fold-takes-the-fee-from-the-fact-test
  (let [events [(fixture/opened) (fixture/deposited 1000M) (fixture/withdrawn 100M 5M fixture/january)]]
    (is (= 895M (account/balance events)))
    (testing "and there is no configuration that could change it"
      (is (= 895M (account/balance events))))))
