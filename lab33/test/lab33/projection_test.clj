(ns lab33.projection-test
  "Rebuild and reclassify are the same operation, and whether that is correct
  depends on the question."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.fixture :as fixture]
            [lab33.projection :as projection]
            [lab33.rules :as rules]
            [lab33.rules.stream :as rules-stream]))

(def movements
  "Three withdrawals across the year, either side of a threshold change."
  [(fixture/opened)
   (assoc-in (fixture/withdrawn 12000M 0M fixture/january) [:data :amount] 12000M)
   (assoc (assoc-in (fixture/withdrawn 12000M 0M fixture/december) [:data :amount] 12000M)
          :event/id #uuid "00000000-0000-4000-8000-00000000000d")
   (assoc (assoc-in (fixture/withdrawn 20000M 0M fixture/december) [:data :amount] 20000M)
          :event/id #uuid "00000000-0000-4000-8000-00000000000e")])

(def raised-in-june
  [(rules-stream/changed :reporting-threshold 15000M fixture/june
                         "regulator" "threshold raised for 2026 H2")])

(deftest the-current-view-classifies-everything-against-one-number-test
  (let [old (rules/configure {:reporting-threshold 10000M})
        new (rules/configure {:reporting-threshold 15000M})]
    (is (= 3 (count (projection/flagged old movements))))
    (is (= 1 (count (projection/flagged new movements))))
    (testing "which is correct for 'what is reportable now' and for nothing else"
      (is (= [20000M] (mapv :amount (projection/flagged new movements)))))))

(deftest rebuilding-a-current-view-reclassifies-the-past-test
  ;; Not a bug — it is what a current view is for. It becomes a bug the moment
  ;; somebody uses this table to answer a question about a closed period.
  (let [before (projection/flagged (rules/configure {:reporting-threshold 10000M}) movements)
        after  (projection/flagged (rules/configure {:reporting-threshold 15000M}) movements)]
    (is (not= before after))
    (is (< (count after) (count before))
        "a January transaction stopped being reportable because of a change in June")))

(deftest the-as-of-view-cannot-be-moved-test
  ;; The same three movements, each judged by the threshold that was in force
  ;; when it happened. January's 12,000 was reportable and stays reportable;
  ;; December's is not, because by then the number had changed.
  (let [flagged (projection/flagged-as-of raised-in-june movements)]
    (is (= 2 (count flagged)))
    (is (= [12000M 20000M] (mapv :amount flagged)))
    (testing "and each row says which number was applied to it"
      (is (= [10000M 15000M] (mapv :threshold-applied flagged))))))

(deftest the-as-of-view-does-not-depend-on-configuration-at-all-test
  ;; It takes the rules' own history and never a config map, which is why no
  ;; edit to a file can reach it.
  (is (= (projection/flagged-as-of raised-in-june movements)
         (projection/flagged-as-of raised-in-june movements))))

(deftest a-later-change-does-not-disturb-earlier-answers-test
  (let [answered-before (projection/flagged-as-of raised-in-june movements)
        raised-again    (conj raised-in-june
                              (rules-stream/changed :reporting-threshold 25000M
                                                    (fixture/instant "2027-06-01T00:00:00Z")
                                                    "regulator" "2027 threshold"))]
    (is (= answered-before (projection/flagged-as-of raised-again movements))
        "a 2027 change reclassifies nothing in 2026")))

(deftest the-rule-itself-is-testable-without-configuration-test
  ;; The shape this lab argues for: a named function taking its parameter as
  ;; an argument. Both projections differ only in where they get the number.
  (is (true? (projection/reportable? 10000M 10000.01M)))
  (is (false? (projection/reportable? 10000M 10000M)) "strictly greater than")
  (is (false? (projection/reportable? 10000M 9999M))))

(deftest the-projection-reads-the-fact-and-not-the-reasoning-test
  ;; The metadata recording why a withdrawal was permitted is Accounts'
  ;; business. A read model that started keying off another module's decision
  ;; inputs would be coupled to its rules rather than its facts.
  (let [flagged (projection/flagged (rules/configure {}) movements)]
    (is (every? #(= #{:amount :fee :direction :occurred-at} (set (keys %))) flagged))))
