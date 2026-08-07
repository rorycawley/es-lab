(ns cart.adapter.driving.http
  "Driving HTTP adapter. JSON/Ring concerns live here; command handling stays in
   cart.app.handle."
  (:require [cart.app.handle :as handle]
            [cart.core :as core]
            [cart.port.event-store :as store]
            [cart.schema :as schema]
            [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]]))

(def ^:private json-content-type "application/json; charset=utf-8")

(defn stream-id
  "HTTP cart ids map onto the same stream-id convention used by the application
   tests and Postgres stream_type derivation."
  [cart-id]
  (str "shopping_cart-" cart-id))

(defn- json-response [status body]
  {:status  status
   :headers {"content-type" json-content-type}
   :body    (json/generate-string body)})

(defn- parse-json-body [request]
  (try
    (let [body (:body request)]
      (if body
        (let [text (slurp body)]
          (if (str/blank? text)
            {}
            (json/parse-string text true)))
        {}))
    (catch Exception e
      (throw (ex-info "Invalid JSON body" {:type ::invalid-json} e)))))

(defn- expected-version-token [request]
  (get-in request [:params "expected-version"]))

(defn- parse-expected-version [token]
  (cond
    (nil? token) nil
    (= "any" token) :any
    (= "stream-does-not-exist" token) :stream-does-not-exist
    (re-matches #"\d+" token) (Long/parseLong token)
    :else ::invalid-expected-version))

(defn- keyword-command-type [type]
  (if (string? type) (keyword type) type))

(defn- request-error [status body]
  (throw (ex-info (:error body) {:type ::request-error
                                 :status status
                                 :body body})))

(defn- normalize-command [cart-id clock body]
  (when-not (map? body)
    (request-error 400 {:error "invalid-command"
                        :details "Request body must be a JSON object."}))
  (let [data (:data body)]
    (when (and (some? data) (not (map? data)))
      (request-error 400 {:error "invalid-command"
                          :details "Command data must be a JSON object."}))
    (let [body-cart-id (:cart-id data)]
      (when (and body-cart-id (not= cart-id body-cart-id))
        (request-error 400 {:error        "cart-id-mismatch"
                            :path-cart-id cart-id
                            :body-cart-id body-cart-id})))
    (-> body
        (update :type keyword-command-type)
        (assoc :data (assoc (or data {}) :cart-id cart-id))
        (update :metadata #(or % {:now (long (clock))})))))

(defn- command-errors [command]
  (some-> (m/explain schema/Command command) me/humanize))

(defn- command-response [cart-id stream-id [outcome data]]
  (case outcome
    :ok
    (json-response (if (:created-new-stream? data) 201 200)
                   (assoc data :cart-id cart-id :stream-id stream-id))

    :error
    (json-response 422 (assoc data :error "command-rejected"))

    :conflict
    (json-response 409 (assoc data :error "version-conflict"))))

(defn- handle-post-command [{:keys [event-store retry clock]} request]
  (let [cart-id  (get-in request [:path-params :cart-id])
        stream   (stream-id cart-id)
        expected (parse-expected-version (expected-version-token request))]
    (if (= ::invalid-expected-version expected)
      (json-response 400 {:error            "invalid-expected-version"
                          :expected-version (expected-version-token request)})
      (try
        (let [command (normalize-command cart-id clock (parse-json-body request))]
          (if-let [errors (command-errors command)]
            (json-response 400 {:error "invalid-command" :details errors})
            (command-response
             cart-id
             stream
             (if (nil? expected)
               (handle/handle-command {:event-store event-store :retry retry}
                                      stream
                                      command)
               (handle/handle-command {:event-store event-store :retry retry}
                                      stream
                                      command
                                      expected)))))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type status body]} (ex-data e)]
            (case type
              ::invalid-json (json-response 400 {:error "invalid-json"})
              ::request-error (json-response status body)
              (throw e))))))))

(defn- handle-get-events [event-store request]
  (let [cart-id (get-in request [:path-params :cart-id])
        stream  (stream-id cart-id)]
    (json-response 200 (assoc (store/read-stream event-store stream)
                              :cart-id cart-id
                              :stream-id stream))))

(defn- handle-get-cart [event-store request]
  (let [cart-id (get-in request [:path-params :cart-id])
        stream  (stream-id cart-id)
        read    (store/read-stream event-store stream)]
    (json-response 200 {:cart-id   cart-id
                        :stream-id stream
                        :exists?   (:exists? read)
                        :version   (:version read)
                        :state     (core/fold (:events read))})))

(defn- routes [deps]
  [["/health"
    {:get (fn [_] (json-response 200 {:status "ok"}))}]

   ["/carts/:cart-id"
    {:get (fn [request] (handle-get-cart (:event-store deps) request))}]

   ["/carts/:cart-id/events"
    {:get (fn [request] (handle-get-events (:event-store deps) request))}]

   ["/carts/:cart-id/commands"
    {:post (fn [request] (handle-post-command deps request))}]])

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
   {:event-store <EventStore>
    :retry       optional retry config for cart.app.handle
    :clock       optional zero-arg fn returning epoch millis}"
  [{:keys [event-store clock] :as deps}]
  (when-not event-store
    (throw (ex-info "HTTP handler requires :event-store" {})))
  (let [deps (assoc deps :clock (or clock #(System/currentTimeMillis)))]
    (-> (ring/ring-handler
         (ring/router (routes deps))
         (default-handler))
        wrap-params
        wrap-unhandled)))
