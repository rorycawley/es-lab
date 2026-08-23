(ns lab33.policy-test
  "The safe home, and the one trap in it."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.account :as account]
            [lab33.fixture :as fixture]
            [lab33.policy :as policy]
            [lab33.rules :as rules]))

(def sensible (rules/configure {:sweep-amount 100M :review-above 50000M}))

(defn- withdrawal [amount]
  (assoc-in (fixture/withdrawn amount) [:data :amount] amount))

;; ---------------------------------------------------------------------------
;; Configuring it changes what is asked for — which is the point
;; ---------------------------------------------------------------------------

(deftest a-configured-threshold-changes-which-reactions-fire-test
  (is (= [:withdraw]
         (mapv :command/type (policy/react sensible (withdrawal 100M))))
      "a small withdrawal sweeps and nothing else")
  (is (= [:withdraw :flag-for-review]
         (mapv :command/type (policy/react sensible (withdrawal 60000M))))
      "a large one also asks for a review"))

(deftest the-threshold-is-strictly-greater-than-test
  (is (= [:withdraw] (mapv :command/type (policy/react sensible (withdrawal 50000M)))))
  (is (= [:withdraw :flag-for-review]
         (mapv :command/type (policy/react sensible (withdrawal 50000.01M))))))

;; ---------------------------------------------------------------------------
;; The trap
;; ---------------------------------------------------------------------------

(deftest the-command-id-does-not-move-when-configuration-does-test
  ;; The one-word mistake with no symptom until somebody edits a number.
  ;;
  ;; A reactor is fed by at-least-once delivery. If the derived id included the
  ;; swept amount, then changing that amount would make the redelivery of an
  ;; old event produce a *different* id — which deduplicates against nothing,
  ;; and the account is swept twice.
  (let [event  (withdrawal 100M)
        cheap  (rules/configure {:sweep-amount 50M})
        dear   (rules/configure {:sweep-amount 900M})
        id-of  (fn [config] (:command/id (first (policy/react config event))))]
    (is (= (id-of cheap) (id-of dear))
        "the id is derived from the policy and the event, and from nothing else")
    (testing "while the request itself did change"
      (is (not= (first (policy/react cheap event)) (first (policy/react dear event)))))))

(deftest redelivery-of-the-same-event-asks-for-the-same-thing-test
  (let [event (withdrawal 100M)]
    (is (= (policy/react sensible event) (policy/react sensible event)))))

(deftest two-policies-on-one-event-do-not-collide-test
  (let [[sweep review] (policy/react sensible (withdrawal 60000M))]
    (is (not= (:command/id sweep) (:command/id review)))))

;; ---------------------------------------------------------------------------
;; Blast radius: a misconfigured policy asks for something the aggregate refuses
;; ---------------------------------------------------------------------------

(deftest a-misconfigured-policy-cannot-corrupt-anything-test
  ;; Somebody sets the sweep to 900 on an account holding 100. The policy
  ;; dutifully asks. The savings account's `decide` — which owns the invariant
  ;; and does not care who is asking — refuses, and the history is untouched.
  ;;
  ;; This is the property that makes a policy the right place for a number
  ;; somebody will get wrong.
  (let [savings   {:status :open :balance 100M}
        misconfig (rules/configure {:sweep-amount 900M :overdraft-limit 0M})
        [command] (policy/react misconfig (withdrawal 100M))]
    (is (= :withdraw (:command/type command)))
    (is (= :insufficient-funds
           (fixture/reason #(account/decide command savings)))
        "the aggregate refuses on its own authority")))

(deftest a-policy-writes-nothing-test
  ;; It returns requests. There is no arrangement of configuration that lets
  ;; it record a fact, which is the structural reason a change here cannot
  ;; reach the past.
  (let [history [(fixture/opened) (fixture/deposited 500M)]
        before  (account/replay history)]
    (policy/react-to-all sensible history)
    (is (= before (account/replay history)))))

(deftest an-unknown-event-stops-the-reader-test
  ;; Lab 10's rule, unchanged: an event type this policy has not been taught
  ;; might require a reaction, so checkpointing silently past it loses work.
  (is (= :unknown-event-type
         (fixture/reason #(policy/react sensible {:event/id fixture/event-1
                                                  :event/type :account-frozen
                                                  :data {}})))))
