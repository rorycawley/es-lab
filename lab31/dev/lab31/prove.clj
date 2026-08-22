(ns lab31.prove
  "Run the scoped, held-out performance claims and fail when they do not hold."
  (:require [clojure.pprint :as pprint]
            [lab31.performance.experiment :as experiment]
            [lab31.performance.search :as search]
            [lab31.performance.system :as system]
            [lab31.performance.workload :as workload]))

(def proof-config
  {:corpus-size 20000
   :latency-ms  10
   :warmup      2
   :trials      7})

(def claims
  {:local-index
   {:minimum-speedup 5.0
    :minimum-win-rate 0.85}

   :boundary-round-trips
   {:minimum-speedup     5.0
    :minimum-win-rate    0.85
    :candidate-at-most-ms 60.0
    :baseline-at-least-ms 75.0}})

(defn- same-answer! [expected-ids named-results]
  (let [answers    (mapv (comp :found second) named-results)
        answer-ids (mapv #(mapv (fn [entity] (:entity-id entity)) %) answers)]
    (when-not (and (apply = answers)
                   (every? #(= expected-ids %) answer-ids))
      (throw (ex-info "Implementations disagree; timing them would prove nothing"
                      {:expected-ids expected-ids
                       :results      (into {} named-results)})))
    (first answers)))

(defn run-proof
  ([] (run-proof proof-config))
  ([{:keys [corpus-size latency-ms warmup trials] :as config}]
   (let [entities       (workload/corpus corpus-size)
         registrations  (workload/proof-keys corpus-size)
         index          (search/build-index entities)
         scan-local     #(system/local-journey
                          (partial search/scan-many entities)
                          registrations)
         indexed-local  #(system/local-journey
                          (partial search/indexed-many index)
                          registrations)
         gateway        (system/simulated-gateway
                         index
                         (partial search/indexed-many index)
                         latency-ms)
         indexed-chatty #(system/chatty-journey gateway registrations)
         indexed-batch  #(system/batched-journey gateway registrations)
         answer         (same-answer! (workload/proof-expected-ids corpus-size)
                                      [[:scan-local (scan-local)]
                                       [:indexed-local (indexed-local)]
                                       [:indexed-chatty (indexed-chatty)]
                                       [:indexed-batch (indexed-batch)]])
         local-result   (experiment/comparison
                         (experiment/paired-trials
                          {:baseline  scan-local
                           :candidate indexed-local
                           :warmup    warmup
                           :trials    trials}))
         boundary-result (experiment/comparison
                          (experiment/paired-trials
                           {:baseline  indexed-chatty
                            :candidate indexed-batch
                            :warmup    warmup
                            :trials    trials}))
         results        {:local-index local-result
                         :boundary-round-trips boundary-result}
         assessments    (into {}
                              (map (fn [[claim result]]
                                     [claim (experiment/assess
                                             result
                                             (get claims claim))]))
                              results)]
     {:config      config
      :environment (experiment/environment)
      :workload    {:queries       (count registrations)
                    :hits          (count (remove nil? answer))
                    :misses        (count (filter nil? answer))
                    :tuning-keys   (workload/tuning-keys corpus-size)
                    :proof-keys    registrations}
      :results     results
      :assessments assessments
      :pass?       (every? (comp :pass? second) assessments)})))

(defn- millis [nanoseconds]
  (/ nanoseconds 1e6))

(defn- print-comparison [title baseline-name candidate-name result assessment]
  (println title)
  (println (format "  %-18s p50 %8.3f ms   p95 %8.3f ms"
                   baseline-name
                   (millis (get-in result [:baseline :p50-ns]))
                   (millis (get-in result [:baseline :p95-ns]))))
  (println (format "  %-18s p50 %8.3f ms   p95 %8.3f ms"
                   candidate-name
                   (millis (get-in result [:candidate :p50-ns]))
                   (millis (get-in result [:candidate :p95-ns]))))
  (println (format "  paired median speedup %.2fx; candidate won %.0f%% of trials"
                   (:paired-speedup-p50 result)
                   (* 100 (:candidate-win-rate result))))
  (doseq [{:keys [name expected actual pass?]} (:checks assessment)]
    (println (format "  %s %-24s expected %-7s actual %.3f"
                     (if pass? "PASS" "FAIL")
                     (clojure.core/name name)
                     expected
                     (double actual))))
  (println))

(defn -main [& _]
  (let [{:keys [config environment workload results assessments pass?]}
        (run-proof)]
    (println "Lab 31 — scoped performance proof")
    (println)
    (println "Environment (evidence, not decoration):")
    (pprint/pprint environment)
    (println)
    (println (format (str "Workload: %,d retained entities, %d held-out exact lookups "
                          "(%d hits, %d miss), %d ms controlled latency per boundary call")
                     (:corpus-size config)
                     (:queries workload)
                     (:hits workload)
                     (:misses workload)
                     (:latency-ms config)))
    (println (format "Protocol: %d warmups, %d alternating paired trials"
                     (:warmup config) (:trials config)))
    (println "Correctness: all four journeys returned the same ordered answer")
    (println)
    (print-comparison "Claim 1: specialize the bounded compute"
                      "linear scan" "prebuilt index"
                      (get results :local-index)
                      (get assessments :local-index))
    (print-comparison "Claim 2: remove repeated boundary waits"
                      (format "%d indexed calls" (:queries workload))
                      "1 indexed batch"
                      (get results :boundary-round-trips)
                      (get assessments :boundary-round-trips))
    (println (if pass?
               "PROVED for this workload and environment."
               "NOT PROVED for this workload and environment."))
    (when-not pass?
      (throw (ex-info "Performance proof failed"
                      {:assessments assessments})))))
