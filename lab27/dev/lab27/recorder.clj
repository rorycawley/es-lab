(ns lab27.recorder
  "In-memory collectors, so telemetry can be asserted instead of admired.

  This namespace lives in `dev/` for the same reason lab 24's identity provider
  did: `opentelemetry-sdk-testing` is a dependency of your tests and never of
  your application. `architecture_test.clj` asserts nothing under `src/` names
  it.

  The SDK is started once per JVM — `init-otel-sdk!` installs a default
  instance and a Logback appender, and doing that twice per test would be
  starting a different application each time. Between scenarios the collectors
  are reset instead."
  (:require [lab27.system :as system])
  (:import (io.opentelemetry.api.common Attributes)
           (io.opentelemetry.sdk.logs.data LogRecordData)
           (io.opentelemetry.sdk.testing.exporter InMemoryLogRecordExporter
                                                  InMemoryMetricReader
                                                  InMemorySpanExporter)
           (io.opentelemetry.sdk.trace.data SpanData)))

(def ^:private span-exporter (InMemorySpanExporter/create))
(def ^:private log-exporter (InMemoryLogRecordExporter/create))
(def ^:private metric-reader (InMemoryMetricReader/create))

(defonce ^:private started
  (delay (system/start-telemetry! {:spans   [span-exporter]
                                   :logs    [log-exporter]
                                   :metrics [metric-reader]})))

(defn start! [] @started)

(defn clear! []
  @started
  (.reset span-exporter)
  (.reset log-exporter))

(defn spans
  "Every span finished since the last reset, oldest first.

  Exported synchronously — the SDK is configured with unbatched processors, so
  a span is here the moment it ends and no test has to sleep."
  []
  (vec (.getFinishedSpanItems span-exporter)))

(defn logs []
  (vec (.getFinishedLogRecordItems log-exporter)))

(defn metrics []
  (vec (.collectAllMetrics metric-reader)))

;; ---------------------------------------------------------------------------
;; Reading telemetry back as ordinary Clojure data
;; ---------------------------------------------------------------------------

(defn- attributes->map [^Attributes attributes]
  (into {} (map (fn [[k v]] [(.getKey k) v])) (.asMap attributes)))

(defn span->map [^SpanData span]
  {:name        (.getName span)
   :trace-id    (.getTraceId span)
   :span-id     (.getSpanId span)
   :parent-id   (let [parent (.getParentSpanContext span)]
                  (when (.isValid parent) (.getSpanId parent)))
   :kind        (keyword (.name (.getKind span)))
   :status      (keyword (.name (.getStatusCode (.getStatus span))))
   :events      (mapv #(.getName %) (.getEvents span))
   :attributes  (attributes->map (.getAttributes span))
   :duration-ms (quot (- (.getEndEpochNanos span) (.getStartEpochNanos span)) 1000000)})

(defn log->map [^LogRecordData record]
  (let [span-context (.getSpanContext record)]
    {:body       (some-> (.getBodyValue record) .getValue str)
     :trace-id   (when (.isValid span-context) (.getTraceId span-context))
     :span-id    (when (.isValid span-context) (.getSpanId span-context))
     :attributes (attributes->map (.getAttributes record))}))

(defn recorded-spans [] (mapv span->map (spans)))
(defn recorded-logs [] (mapv log->map (logs)))

(defn span-named [name]
  (first (filter #(= name (:name %)) (recorded-spans))))

(defn counter-values
  "The `lab27.slice.requests` counter as `{attributes value}`."
  []
  (into {}
        (for [metric (metrics)
              :when (= "lab27.slice.requests" (.getName metric))
              point (.getPoints (.getData metric))]
          [(attributes->map (.getAttributes point)) (.getValue point)])))
