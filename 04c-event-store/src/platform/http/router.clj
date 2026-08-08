(ns platform.http.router
  "Operational HTTP adapter for the walking skeleton."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]))

(def ^:private content-type "application/json; charset=utf-8")

(def ^:private openapi-document
  (delay (slurp (io/resource "openapi/cart-api.openapi.json"))))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" content-type}
   :body (json/generate-string body)})

(defn- json-document-response [body]
  {:status 200
   :headers {"content-type" content-type}
   :body body})

(defn- routes [{:keys [ready?]}]
  [["/health"
    {:get (fn [_]
            (json-response 200 {:status "ok"}))}]
   ["/ready"
    {:get (fn [_]
            (if (ready?)
              (json-response 200 {:status "ready"})
              (json-response 503 {:status "not-ready"})))}]
   ["/openapi.json"
    {:get (fn [_]
            (json-document-response @openapi-document))}]])

(defn- default-handler []
  (ring/create-default-handler
   {:not-found
    (constantly (json-response 404 {:outcome "invalid"
                                    :code "route-not-found"}))
    :method-not-allowed
    (constantly (json-response 405 {:outcome "invalid"
                                    :code "method-not-allowed"}))
    :not-acceptable
    (constantly (json-response 406 {:outcome "invalid"
                                    :code "not-acceptable"}))}))

(defn handler
  ([] (handler {}))
  ([{:keys [ready?]
     :or {ready? (constantly true)}}]
   (when-not (ifn? ready?)
     (throw (ex-info "HTTP handler requires a readiness function" {})))
   (ring/ring-handler (ring/router (routes {:ready? ready?}))
                      (default-handler))))
