(ns lab31.experiment-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab31.performance.experiment :as experiment]))

(deftest percentile-uses-nearest-rank-test
  (let [samples [50 10 40 20 30]]
    (is (= 10 (experiment/percentile samples 0)))
    (is (= 30 (experiment/percentile samples 0.5)))
    (is (= 50 (experiment/percentile samples 0.95)))
    (is (= 50 (experiment/percentile samples 1)))))

(deftest paired-comparison-preserves-each-trial-test
  (let [pairs [{:trial 0 :first :baseline :baseline-ns 100 :candidate-ns 20}
               {:trial 1 :first :candidate :baseline-ns 120 :candidate-ns 30}
               {:trial 2 :first :baseline :baseline-ns 80 :candidate-ns 40}]
        result (experiment/comparison pairs)]
    (is (= 100 (get-in result [:baseline :p50-ns])))
    (is (= 30 (get-in result [:candidate :p50-ns])))
    (is (= 4.0 (:paired-speedup-p50 result)))
    (is (= 1.0 (:candidate-win-rate result)))
    (is (= pairs (:pairs result)))))

(deftest claims-are-evaluated-without-moving-the-threshold-test
  (let [result {:baseline {:p50-ns 120000000}
                :candidate {:p50-ns 15000000}
                :paired-speedup-p50 8.0
                :candidate-win-rate 1.0}
        assessment (experiment/assess
                    result
                    {:minimum-speedup 5.0
                     :minimum-win-rate 0.85
                     :candidate-at-most-ms 60
                     :baseline-at-least-ms 75})]
    (is (:pass? assessment))
    (is (every? :pass? (:checks assessment))))

  (testing "a missed budget makes the proof fail"
    (let [assessment (experiment/assess
                      {:baseline {:p50-ns 120000000}
                       :candidate {:p50-ns 61000000}
                       :paired-speedup-p50 1.97
                       :candidate-win-rate 1.0}
                      {:minimum-speedup 5.0
                       :candidate-at-most-ms 60})]
      (is (false? (:pass? assessment)))
      (is (= #{:minimum-speedup :candidate-at-most-ms}
             (into #{} (comp (remove :pass?) (map :name))
                   (:checks assessment)))))))
