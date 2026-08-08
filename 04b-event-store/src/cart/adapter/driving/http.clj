(ns cart.adapter.driving.http
  "Driving HTTP adapter. JSON/Ring concerns live here; command and query work
   stays behind application ports."
  (:require [cart.port.cart-command :as command]
            [cart.port.cart-query :as query]
            [cart.schema :as schema]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [reitit.ring :as ring])
  (:import [java.nio.charset StandardCharsets]))

(def ^:private json-content-type "application/json; charset=utf-8")
(def ^:private openapi-contract
  (delay (slurp (io/resource "openapi/cart-api.openapi.json"))))

(defn- json-response [status body]
  {:status  status
   :headers {"content-type" json-content-type}
   :body    (json/generate-string body)})

(defn- json-text-response [status body]
  {:status  status
   :headers {"content-type" json-content-type}
   :body    body})

(defn- parse-json-body [request]
  (try
    (let [body (:body request)]
      (if body
        (let [text (slurp body :encoding (.name StandardCharsets/UTF_8))]
          (if (str/blank? text)
            {}
            (json/parse-string text true)))
        {}))
    (catch Exception e
      (throw (ex-info "Invalid JSON body" {:type ::invalid-json} e)))))

(defn- parse-expected-version [token]
  (cond
    (nil? token) nil
    (= "any" token) :any
    (= "stream-does-not-exist" token) :stream-does-not-exist
    (and (integer? token) (not (neg? token))) token
    :else ::invalid-expected-version))

(defn- request-error [status body]
  (throw (ex-info (:error body) {:type ::request-error
                                 :status status
                                 :body body})))

(defn- ensure-object! [error body]
  (when-not (map? body)
    (request-error 400 {:error error
                        :details "Request body must be a JSON object."})))

(defn- ensure-allowed-keys! [error body allowed]
  (let [unknown (seq (remove allowed (keys body)))]
    (when unknown
      (request-error 400 {:error error
                          :details {:unknown-keys (mapv name unknown)}}))))

(defn- expected-version [body]
  (let [token  (:expected-version body)
        parsed (parse-expected-version token)]
    (when (= ::invalid-expected-version parsed)
      (request-error 400 {:error            "invalid-expected-version"
                          :expected-version token}))
    parsed))

(defn- normalize-task-command [type data-keys clock body]
  (ensure-object! "invalid-command" body)
  (ensure-allowed-keys! "invalid-command"
                        body
                        (into #{:cart-id :expected-version :metadata} data-keys))
  (let [metadata (or (:metadata body) {:now (long (clock))})
        data     (merge {:cart-id (:cart-id body)} (select-keys body data-keys))]
    [(expected-version body)
     {:type     type
      :data     data
      :metadata metadata}]))

(defn- command-errors [command]
  (some-> (m/explain schema/Command command) me/humanize))

(defn- command-response [[outcome data]]
  (case outcome
    :ok
    (json-response (if (:created-new-stream? data) 201 200) data)

    :error
    (json-response 422 (assoc data :error "command-rejected"))

    :conflict
    (json-response 409 (assoc data :error "version-conflict"))))

(defn- handle-request-error [e]
  (let [{:keys [type status body]} (ex-data e)]
    (case type
      ::invalid-json (json-response 400 {:error "invalid-json"})
      ::request-error (json-response status body)
      (throw e))))

(defn- handle-command-task [deps type data-keys request]
  (try
    (let [[expected command] (normalize-task-command type
                                                     data-keys
                                                     (:clock deps)
                                                     (parse-json-body request))]
      (if-let [errors (command-errors command)]
        (json-response 400 {:error "invalid-command" :details errors})
        (command-response
         (if (nil? expected)
           (command/handle-cart-command (:cart-command deps)
                                        (get-in command [:data :cart-id])
                                        command)
           (command/handle-cart-command (:cart-command deps)
                                        (get-in command [:data :cart-id])
                                        command
                                        expected)))))
    (catch clojure.lang.ExceptionInfo e
      (handle-request-error e))))

(defn- query-cart-id [body]
  (ensure-object! "invalid-query" body)
  (ensure-allowed-keys! "invalid-query" body #{:cart-id})
  (let [cart-id (:cart-id body)]
    (when-not (and (string? cart-id) (not (str/blank? cart-id)))
      (request-error 400 {:error "invalid-query"
                          :details "cart-id is required."}))
    cart-id))

(defn- handle-query [f request]
  (try
    (json-response 200 (f (query-cart-id (parse-json-body request))))
    (catch clojure.lang.ExceptionInfo e
      (handle-request-error e))))

(defn- routes [deps]
  [["/health"
    {:get (fn [_] (json-response 200 {:status "ok"}))}]

   ["/openapi.json"
    {:get (fn [_] (json-text-response 200 @openapi-contract))}]

   ["/commands/add-product-item"
    {:post (fn [request]
             (handle-command-task deps
                                  :cart.command/add-product-item
                                  [:product-item]
                                  request))}]

   ["/commands/remove-product-item"
    {:post (fn [request]
             (handle-command-task deps
                                  :cart.command/remove-product-item
                                  [:product-item]
                                  request))}]

   ["/commands/confirm-cart"
    {:post (fn [request]
             (handle-command-task deps :cart.command/confirm [] request))}]

   ["/commands/cancel-cart"
    {:post (fn [request]
             (handle-command-task deps :cart.command/cancel [] request))}]

   ["/queries/get-cart"
    {:post (fn [request]
             (handle-query #(query/cart-summary (:cart-query deps) %) request))}]

   ["/queries/get-cart-events"
    {:post (fn [request]
             (handle-query #(query/cart-events (:cart-query deps) %) request))}]])

(defn- default-handler []
  (ring/create-default-handler
   {:not-found
    (constantly (json-response 404 {:error "not-found"}))

    :method-not-allowed
    (constantly (json-response 405 {:error "method-not-allowed"}))

    :not-acceptable
    (constantly (json-response 406 {:error "not-acceptable"}))}))

(defn- wrap-unhandled [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception _
        (json-response 500 {:error "internal-server-error"})))))

(defn handler
  "Builds a Ring handler.

   deps:
   {:cart-command <CartCommand>
    :cart-query   <CartQuery>
    :clock       optional zero-arg fn returning epoch millis}"
  [{:keys [cart-command cart-query clock] :as deps}]
  (when-not cart-command
    (throw (ex-info "HTTP handler requires :cart-command" {})))
  (when-not cart-query
    (throw (ex-info "HTTP handler requires :cart-query" {})))
  (let [deps (assoc deps :clock (or clock #(System/currentTimeMillis)))]
    ;; No wrap-params: every input is a JSON body, and nothing reads :params or
    ;; :query-params. Parsing them would be per-request work on the path that
    ;; carries a latency budget.
    (-> (ring/ring-handler
         (ring/router (routes deps))
         (default-handler))
        wrap-unhandled)))
