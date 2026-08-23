(ns lab33.rules-stream-test
  "Configuration as an event stream: the version that can answer an auditor."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.fixture :as fixture]
            [lab33.rules :as rules]
            [lab33.rules.stream :as rules-stream]))

(def changes
  [(rules-stream/changed :reporting-threshold 12000M fixture/march
                         "compliance-team" "annual review")
   (rules-stream/changed :reporting-threshold 15000M fixture/june
                         "regulator" "statutory instrument 2026/114")
   (rules-stream/changed :overdraft-limit 500M fixture/june
                         "product" "overdraft launched")])

(deftest a-parameter-change-is-a-fact-with-the-fields-a-fact-has-test
  ;; What a file on disk has nowhere to put: who, why, and from when — as
  ;; distinct from when it was typed.
  (let [{:keys [data]} (first changes)]
    (is (= :reporting-threshold (:parameter data)))
    (is (= 12000M (:value data)))
    (is (= fixture/march (:effective-from data)))
    (is (= "compliance-team" (:changed-by data)))
    (is (= "annual review" (:reason data)))))

(deftest as-of-folds-a-prefix-test
  (let [threshold (fn [at] (rules/parameter (rules-stream/as-of changes at) :reporting-threshold))]
    (is (= 10000M (threshold fixture/january)) "before any change, the default stands")
    (is (= 12000M (threshold fixture/march)))
    (is (= 15000M (threshold fixture/june)))
    (is (= 15000M (threshold fixture/december)))))

(deftest a-change-takes-effect-on-its-effective-date-not-its-entry-date-test
  ;; Lab 18's two axes, in the smallest system where they diverge. A change
  ;; entered today, effective from the first of the month, is backdated — and
  ;; the answer to *what was the threshold in March* is not *what did we know
  ;; in March*.
  (let [mid-february (fixture/instant "2026-02-15T00:00:00Z")
        entered-late (conj changes
                           (rules-stream/changed :reporting-threshold 11000M
                                                 (fixture/instant "2026-02-01T00:00:00Z")
                                                 "compliance-team" "correction, backdated"))
        threshold    (fn [at] (rules/parameter (rules-stream/as-of entered-late at)
                                               :reporting-threshold))]
    (is (= 10000M (threshold fixture/january))
        "January is before the correction takes effect, so it is untouched")
    (is (= 11000M (threshold mid-february))
        "February now answers differently than it did before the correction existed")
    (is (= 12000M (threshold fixture/march))
        "and March is unmoved, because a later change already superseded it")))

(deftest parameters-are-independent-test
  (let [in-june (rules-stream/as-of changes fixture/june)]
    (is (= 15000M (rules/parameter in-june :reporting-threshold)))
    (is (= 500M (rules/parameter in-june :overdraft-limit)))
    (is (= 0M (rules/parameter in-june :withdrawal-fee)) "untouched, so still the default")))

(deftest the-current-value-is-the-latest-effective-one-test
  (is (= 15000M (rules/parameter (rules-stream/current changes) :reporting-threshold)))
  (testing "an empty stream is just the defaults"
    (is (= rules/defaults (rules-stream/current [])))))

(deftest a-parameter-history-is-what-an-auditor-asks-for-test
  (let [history (rules-stream/history changes :reporting-threshold)]
    (is (= [12000M 15000M] (mapv :value history)))
    (is (= ["compliance-team" "regulator"] (mapv :changed-by history)))
    (is (= ["annual review" "statutory instrument 2026/114"] (mapv :reason history)))))

(deftest a-change-must-still-be-configuration-test
  ;; The stream is not a way around the closed check. A rule-as-structure
  ;; cannot get in by being recorded as a fact instead of written to a file.
  (is (= :unknown-parameter
         (fixture/reason #(rules-stream/changed :flag-when true fixture/june "x" "y"))))
  (is (= :not-configuration
         (fixture/reason #(rules-stream/changed :reporting-threshold [:> 10000M]
                                                fixture/june "x" "y")))))

(deftest resolving-is-deterministic-test
  ;; It reads no clock. Two calls with the same instant are the same answer,
  ;; today and in five years.
  (is (= (rules-stream/as-of changes fixture/june)
         (rules-stream/as-of changes fixture/june))))

(deftest the-fold-over-the-rules-reads-no-configuration-test
  ;; The recursion terminating: this is an `evolve` over parameter changes, and
  ;; it is the one fold in the lab that is allowed to be about configuration
  ;; because it *is* the configuration rather than a reader of it.
  (let [source (slurp "src/lab33/rules/stream.clj")]
    (is (not (re-find #"\(rules/configure" source))
        "resolving the rules must not depend on a configured value")))
