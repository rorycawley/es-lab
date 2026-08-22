(ns lab31.performance.experiment
  "A small paired-trial harness for end-to-end performance claims."
  (:import (java.lang.management ManagementFactory)))

(defonce ^:private black-hole (volatile! nil))

(defn percentile
  "Nearest-rank percentile. Samples must be non-empty and p must be in [0, 1]."
  [samples p]
  (when-not (and (seq samples) (<= 0 p 1))
    (throw (ex-info "Percentile requires samples and p in [0, 1]"
                    {:samples samples :p p})))
  (let [ordered (vec (sort samples))
        rank    (max 1 (long (Math/ceil (* p (count ordered)))))]
    (nth ordered (dec rank))))

(defn summarize [samples]
  {:samples (count samples)
   :min-ns  (apply min samples)
   :p50-ns  (percentile samples 0.50)
   :p95-ns  (percentile samples 0.95)
   :max-ns  (apply max samples)})

(defn- measure [f]
  (let [started (System/nanoTime)
        value   (f)
        elapsed (- (System/nanoTime) started)]
    (vreset! black-hole value)
    elapsed))

(defn paired-trials
  "Warm both implementations, then alternate which runs first. The result of
  each invocation is retained by a black hole so the work cannot be discarded."
  [{:keys [baseline candidate warmup trials]
    :or   {warmup 2 trials 7}}]
  (when-not (and (pos? trials) (not (neg? warmup)))
    (throw (ex-info "Trials must be positive and warmup non-negative"
                    {:warmup warmup :trials trials})))
  (dotimes [_ warmup]
    (measure baseline)
    (measure candidate))
  (mapv (fn [trial]
          (if (even? trial)
            {:trial        trial
             :first        :baseline
             :baseline-ns  (measure baseline)
             :candidate-ns (measure candidate)}
            (let [candidate-ns (measure candidate)
                  baseline-ns  (measure baseline)]
              {:trial        trial
               :first        :candidate
               :baseline-ns  baseline-ns
               :candidate-ns candidate-ns})))
        (range trials)))

(defn comparison [pairs]
  (let [baseline  (mapv :baseline-ns pairs)
        candidate (mapv :candidate-ns pairs)
        speedups  (mapv #(/ (double (:baseline-ns %))
                            (max 1.0 (double (:candidate-ns %))))
                        pairs)
        wins      (count (filter #(> (:baseline-ns %) (:candidate-ns %)) pairs))]
    {:baseline           (summarize baseline)
     :candidate          (summarize candidate)
     :paired-speedup-p50 (percentile speedups 0.50)
     :candidate-win-rate (/ (double wins) (count pairs))
     :pairs              pairs}))

(defn assess
  "Evaluate only thresholds declared before the samples were observed."
  [result {:keys [minimum-speedup minimum-win-rate
                  candidate-at-most-ms baseline-at-least-ms]}]
  (let [checks (cond-> []
                 minimum-speedup
                 (conj {:name     :minimum-speedup
                        :expected minimum-speedup
                        :actual   (:paired-speedup-p50 result)
                        :pass?    (>= (:paired-speedup-p50 result)
                                      minimum-speedup)})

                 minimum-win-rate
                 (conj {:name     :minimum-win-rate
                        :expected minimum-win-rate
                        :actual   (:candidate-win-rate result)
                        :pass?    (>= (:candidate-win-rate result)
                                      minimum-win-rate)})

                 candidate-at-most-ms
                 (conj {:name     :candidate-at-most-ms
                        :expected candidate-at-most-ms
                        :actual   (/ (get-in result [:candidate :p50-ns]) 1e6)
                        :pass?    (<= (get-in result [:candidate :p50-ns])
                                      (* candidate-at-most-ms 1e6))})

                 baseline-at-least-ms
                 (conj {:name     :baseline-at-least-ms
                        :expected baseline-at-least-ms
                        :actual   (/ (get-in result [:baseline :p50-ns]) 1e6)
                        :pass?    (>= (get-in result [:baseline :p50-ns])
                                      (* baseline-at-least-ms 1e6))}))]
    {:pass?  (every? :pass? checks)
     :checks checks}))

(defn environment []
  (let [runtime (Runtime/getRuntime)
        bean    (ManagementFactory/getRuntimeMXBean)]
    {:java-version  (System/getProperty "java.version")
     :vm-name       (System/getProperty "java.vm.name")
     :os            (System/getProperty "os.name")
     :os-version    (System/getProperty "os.version")
     :architecture  (System/getProperty "os.arch")
     :processors    (.availableProcessors runtime)
     :max-heap-mib  (quot (.maxMemory runtime) (* 1024 1024))
     :jvm-arguments (vec (.getInputArguments bean))}))
