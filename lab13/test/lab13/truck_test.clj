(ns lab13.truck-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab13.corpus :as corpus]
            [lab13.truck :as truck]
            [lab13.upcast :as upcast]))

(deftest the-whole-history-folds-test
  (testing "six events written in five shapes, folded by one function"
    (let [state (truck/replay corpus/every-shape)]
      (is (= {"vanilla" 4 "chocolate" 2} (:sold state))
          "and the tally is keyed by strings, because the ladder ends there"))))

(deftest the-domain-never-sees-a-version-test
  (testing "every event reaching evolve is already current"
    (doseq [event (upcast/read-all corpus/every-shape)]
      (is (not (contains? (:data event) :price))
          "the v2 name is gone by the time the fold runs"))))

(deftest a-missing-price-is-counted-not-guessed-test
  (let [state (truck/replay corpus/every-shape)]
    (is (= 1 (:incomplete state)) "one sale predates price recording")
    (testing "and it contributes nothing to revenue rather than zero-by-accident"
      (is (= (:net state)
             (:net (truck/replay (remove #(= corpus/flavour-sold-v1 %)
                                         corpus/every-shape))))))))

(deftest the-incomplete-count-is-what-makes-the-total-honest-test
  (testing "a reader can tell the difference between £5 and £5-plus-unknowns"
    (let [complete-only (truck/replay [corpus/flavour-sold-v2 corpus/flavour-sold-v3])
          with-v1       (truck/replay corpus/every-shape)]
      (is (zero? (:incomplete complete-only)))
      (is (pos? (:incomplete with-v1)))
      (testing "same arithmetic, different confidence"
        (is (< (:net complete-only) (:net with-v1)) "the gross sale still counts")))))

;; ---------------------------------------------------------------------------
;; The meaning change. This is the section the lab exists for.
;; ---------------------------------------------------------------------------

(def ^:private vat 1.20M)

(deftest gross-and-net-are-different-facts-test
  (testing "both events record 'a cone was sold for a price'"
    (is (= 3.00M (get-in corpus/flavour-sold-gross [:data :unit-price])))
    (is (= 2.50M (get-in corpus/flavour-sold-v3 [:data :unit-price]))))
  (testing "but the numbers are not comparable, and nothing in the shape says so"
    (is (= (set (keys (:data corpus/flavour-sold-v3)))
           (set (keys (:data (upcast/read-event corpus/flavour-sold-gross))))))))

(deftest the-fold-converts-rather-than-adds-blindly-test
  (let [state (truck/replay [corpus/flavour-sold-gross])]
    (is (= (/ 3.00M vat) (:net state))
        "£3.00 inc-VAT is £2.50 ex-VAT")))

(deftest upcasting-the-meaning-change-would-corrupt-the-total-test
  (testing "if :flavour-sold-gross had been shipped as :flavour-sold v4 instead"
    (let [mislabelled (-> corpus/flavour-sold-gross
                          (assoc :event/type :flavour-sold)
                          (assoc-in [:metadata :schema-version]
                                    (upcast/current-version-of :flavour-sold)))
          wrong       (truck/replay [mislabelled])
          right       (truck/replay [corpus/flavour-sold-gross])]
      (is (= 3.00M (:net wrong)) "the gross figure added straight to a net total")
      (is (= (/ 3.00M vat) (:net right)))
      (is (not= (:net wrong) (:net right))
          "a rename cannot fix a meaning change; only a new type can")))
  (testing "and the error is silent — both totals look perfectly plausible"
    (let [mislabelled (-> corpus/flavour-sold-gross
                          (assoc :event/type :flavour-sold)
                          (assoc-in [:metadata :schema-version]
                                    (upcast/current-version-of :flavour-sold)))]
      (is (pos? (:net (truck/replay [mislabelled]))))
      (is (zero? (:incomplete (truck/replay [mislabelled])))))))

(deftest the-domain-rejects-event-semantics-it-does-not-know-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/evolve truck/initial-state
                                      {:event/type :freezer-failed}))))

(deftest both-kinds-of-sale-count-toward-the-same-tally-test
  (testing "the split is in how they are read, not in what they are about"
    (let [state (truck/replay [corpus/flavour-sold-v3 corpus/flavour-sold-gross])]
      (is (= {"chocolate" 1 "vanilla" 1} (:sold state)))
      (is (= (+ 2.50M (/ 3.00M vat)) (:net state))))))
