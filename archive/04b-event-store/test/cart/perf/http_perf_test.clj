(ns cart.perf.http-perf-test
  "Performance smoke tests for the HTTP adapter.

   These are not load tests and they do not measure Jetty, TLS, network, or
   Postgres latency. They catch accidental regressions in the task/query HTTP
   path itself: JSON parsing, validation, routing, command/query dispatch and
   response encoding."
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.adapter.driving.http :as http]
            [cart.app.command :as app-command]
            [cart.app.query :as app-query]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def now 1735689600000)
(def warmup-iterations 200)
(def measured-iterations 1000)

(defn- env-double [name default]
  (let [value (System/getenv name)]
    (if (str/blank? value)
      default
      (Double/parseDouble value))))

(defn- budgets []
  {:query-p95-ms   (env-double "HTTP_PERF_QUERY_P95_MS" 10.0)
   :command-p95-ms (env-double "HTTP_PERF_COMMAND_P95_MS" 15.0)
   :p99-ms         (env-double "HTTP_PERF_P99_MS" 75.0)})

(defn- new-handler []
  (let [event-store (memory/make-store)]
    (http/handler {:cart-command (app-command/make-event-store-command
                                  event-store
                                  {:min-timeout 1})
                   :cart-query   (app-query/make-event-store-query event-store)
                   :clock        (constantly now)})))

(defn- body-stream [body]
  (ByteArrayInputStream.
   (.getBytes (json/generate-string body) StandardCharsets/UTF_8)))

(defn- request [method uri body]
  (cond-> {:request-method method
           :uri            uri}
    body (assoc :headers {"content-type" "application/json"}
                :body (body-stream body))))

(defn- add-item-task [cart-id]
  {"cart-id" cart-id
   "product-item" {"product-id" "sku-1"
                   "quantity" 1
                   "unit-price" 1299}})

(defn- query-cart [cart-id]
  {"cart-id" cart-id})

(defn- invoke! [handler method uri body expected-status]
  (let [response (handler (request method uri body))]
    (when-not (= expected-status (:status response))
      (throw (ex-info "Unexpected HTTP status"
                      {:uri uri
                       :expected expected-status
                       :actual (:status response)
                       :body (:body response)})))
    response))

(defn- elapsed-ms [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (double (- (System/nanoTime) start)) 1000000.0)))

(defn- sample-latencies [n f]
  (mapv (fn [i] (elapsed-ms #(f i))) (range n)))

(defn- percentile [p values]
  (let [ordered (vec (sort values))
        index   (-> (* p (count ordered))
                    Math/ceil
                    long
                    dec
                    (max 0)
                    (min (dec (count ordered))))]
    (nth ordered index)))

(defn- summary [latencies]
  {:count (count latencies)
   :min   (first (sort latencies))
   :p50   (percentile 0.50 latencies)
   :p95   (percentile 0.95 latencies)
   :p99   (percentile 0.99 latencies)
   :max   (last (sort latencies))})

(defn- assert-latency-budget [label latencies p95-budget p99-budget]
  (let [{:keys [p95 p99] :as stats} (summary latencies)]
    (testing label
      (is (<= p95 p95-budget)
          (str label " p95 exceeded budget: " (pr-str stats)
               " budget-ms=" p95-budget))
      (is (<= p99 p99-budget)
          (str label " p99 exceeded budget: " (pr-str stats)
               " budget-ms=" p99-budget)))))

(deftest http-task-and-query-paths-stay-within-latency-budget
  (let [handler (new-handler)
        budget  (budgets)]
    (invoke! handler
             :post
             "/commands/add-product-item"
             (add-item-task "perf-read")
             201)

    (dotimes [i warmup-iterations]
      (invoke! handler
               :post
               "/queries/get-cart"
               (query-cart "perf-read")
               200)
      (invoke! handler
               :post
               "/commands/add-product-item"
               (add-item-task (str "perf-warmup-" i))
               201))

    (let [query-latencies
          (sample-latencies
           measured-iterations
           (fn [_]
             (invoke! handler
                      :post
                      "/queries/get-cart"
                      (query-cart "perf-read")
                      200)))

          command-latencies
          (sample-latencies
           measured-iterations
           (fn [i]
             (invoke! handler
                      :post
                      "/commands/add-product-item"
                      (add-item-task (str "perf-command-" i))
                      201)))]
      (assert-latency-budget "POST /queries/get-cart"
                             query-latencies
                             (:query-p95-ms budget)
                             (:p99-ms budget))
      (assert-latency-budget "POST /commands/add-product-item"
                             command-latencies
                             (:command-p95-ms budget)
                             (:p99-ms budget)))))
