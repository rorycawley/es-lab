(ns lab27.platform.telemetry
  "The one namespace that knows how telemetry is produced.

  Everything else in this lab asks for a span, a log line or a count in the
  language of the application. That seam is not ceremony: an observability API
  is the most viral dependency a codebase acquires, because the reason to adopt
  one is that it belongs *everywhere*. OpenTracing and OpenCensus both looked
  permanent until they merged into OpenTelemetry, and a codebase with four
  hundred direct call sites cannot follow.

  It also protects the rule labs 0 and 21 spent twenty labs on. `price-order`
  decides a price from values, and giving it a way to emit a span would give it
  a way to need one."
  (:require [clojure.string :as str]
            [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [steffan-westcott.clj-otel.context :as context])
  (:import (org.slf4j LoggerFactory MDC)
           (org.slf4j.spi LoggingEventBuilder)))

;; ---------------------------------------------------------------------------
;; Attribute names
;;
;; An OpenTelemetry attribute name is a dotted, snake_cased string — the same
;; shape as `http.response.status_code` in the semantic conventions. clj-otel
;; derives one from a namespaced keyword, so `:es/price-cents` becomes
;; `es.price_cents`. Nothing else in this lab has to know that, but something
;; has to, and this is the file whose job it is.
;;
;; The prefix keeps this application's attributes apart from the conventions'
;; and from any library's. A name with a digit in it would be mangled — `lab27`
;; snake_cases to `lab_26` — which is exactly the kind of detail a seam exists
;; to absorb once instead of everywhere.
;; ---------------------------------------------------------------------------

(def ^:private prefix "es")

(defn- attribute-key [k] (keyword prefix (name k)))
(defn- attribute-name [k] (str prefix "." (str/replace (name k) "-" "_")))

(defn- exportable
  "Coerce a slice's chosen attributes to the primitives OpenTelemetry carries.

  Keywords and UUIDs become strings rather than opaque structured values, so a
  backend can filter on them and a test can read them back."
  [attributes]
  (update-vals attributes
               (fn [v] (if (or (number? v) (boolean? v) (string? v)) v (str v)))))

;; ---------------------------------------------------------------------------
;; Logs
;; ---------------------------------------------------------------------------

(def ^:private logger (LoggerFactory/getLogger "lab27"))

(defn log!
  "Write one structured log event.

  `attributes` are key-value pairs, not string interpolation. A line reading
  `price changed to 300 for 0f1c…` has to be parsed back out by a regex written
  months later, while `es.price_cents=300` arrives at the collector as an
  attribute you can filter on. The trace and span this happens inside are
  attached by the appender from the current context — which is the whole reason
  the logging library is bridged into the SDK rather than replaced by it."
  [message attributes]
  (.log ^LoggingEventBuilder
   (reduce-kv (fn [^LoggingEventBuilder builder k v]
                (.addKeyValue builder (attribute-name k) ^Object v))
              (.setMessage (.atInfo logger) ^String message)
              (exportable attributes))))

;; ---------------------------------------------------------------------------
;; Metrics
;; ---------------------------------------------------------------------------

;; A `delay`, not a `def`: an instrument built before the SDK is configured
;; binds to a no-op meter and silently records nothing for the life of the
;; process.
(defonce ^:private requests
  (delay (instrument/instrument {:name            "lab27.slice.requests"
                                 :instrument-type :counter
                                 :unit            "{request}"
                                 :description     "Requests completed, by outcome"})))

;; ---------------------------------------------------------------------------
;; Traces
;; ---------------------------------------------------------------------------

(defn trace-headers
  "The current trace context as W3C headers, for handing to somebody else.

  This is `{\"traceparent\" \"00-<trace-id>-<span-id>-01\"}`. It is not a
  correlation id — see this lab's README, and note that nothing persists it as
  business data."
  []
  (context/->headers))

(defn- outcome-of
  "The one word that says how a request ended.

  This is the attribute every dashboard groups by, so it is deliberately a
  small closed vocabulary taken from the response the slices already return,
  rather than free text assembled at each call site.

  Lab 27 added `:did-you-mean` and `:no-matches` to it, and got a search
  quality dashboard for nothing: the counter already groups by outcome, so
  the fraction of searches returning nothing was measurable the moment the
  words existed."
  [response]
  (name (cond
          (:accepted response)     :accepted
          (:found response)        :found
          (:did-you-mean response) :did-you-mean
          (:no-matches response)   :no-matches
          (:duplicate response)    :duplicate
          (:not-found response)    :not-found
          (:rejected response)     (:rejected response)
          :else                    :completed)))

(defn observe
  "Run `thunk` inside one span, then log and count what happened.

  | key           | meaning |
  |---------------|---------|
  | `:name`       | qualified request name, e.g. `:catalog/change-price` |
  | `:kind`       | span kind — `:internal`, `:producer`, `:consumer` |
  | `:parent`     | W3C headers carrying a remote context, or nil |
  | `:attributes` | the attributes that may leave the process |

  `:attributes` is an allow-list and is meant to be read as one. Telemetry
  leaves the building: every value here is one you have decided to hand a third
  party and to keep for as long as their retention says. Nothing derives
  attributes from the request map, because the day somebody adds a field to
  that map is the day it starts being exported."
  [{request-name :name :keys [kind parent attributes]} thunk]
  (let [module     (namespace request-name)
        request    (name request-name)
        attributes (exportable attributes)
        counted    (fn [outcome]
                     (instrument/add! @requests
                                      {:value      1
                                       :attributes {:es/module  module
                                                    :es/request request
                                                    :es/outcome outcome}}))
        options    (cond-> {:name       (str module " " request)
                            :span-kind  (or kind :internal)
                            :attributes (assoc (update-keys attributes attribute-key)
                                               :es/module  module
                                               :es/request request)}
                     (seq parent) (assoc :parent (context/headers->merged-context parent)))]
    (span/with-span-binding [context* options]
      (context/with-context! context*
        ;; MDC is restored rather than cleared, because these spans nest: a
        ;; publish calls a consumer, and clearing on the way out of the inner
        ;; one would leave the outer one's remaining log lines untraceable.
        (let [restore (MDC/getCopyOfContextMap)
              span-context (span/get-span-context)]
          (try
            (MDC/put "trace_id" (.getTraceId span-context))
            (MDC/put "span_id" (.getSpanId span-context))
            (let [response (thunk)
                  outcome  (outcome-of response)]
              ;; A refused command is a span that did its job. Only the machine
              ;; failing gets `:error` status — see the README.
              (span/add-span-data! {:attributes {:es/outcome outcome}})
              (counted outcome)
              (log! (str request " " outcome) (assoc attributes :outcome outcome))
              response)
            (catch Throwable t
              ;; `with-span-binding` already records an escaping exception and
              ;; sets the span's status to `:error`. This only counts it.
              (counted "failed")
              (throw t))
            (finally
              (if restore (MDC/setContextMap restore) (MDC/clear)))))))))
