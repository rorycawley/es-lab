(ns es-lab.task-persistence.service-requests.queries
  (:require [clojure.string :as str]
            [es-lab.task-persistence.service-requests.port :as sr]))

(defn- validate-search-query [query]
  (cond
    (not (string? query)) {:status 422 :body {:error "query must be a string"}}
    (str/blank? query)    {:status 422 :body {:error "query cannot be blank"}}
    :else nil))

(defn list-service-requests-handler [{:keys [service-request-port]}]
  (fn [_]
    {:status 200 :body {:requests (sr/list-all service-request-port)}}))

(defn search-service-requests-handler [{:keys [service-request-port]}]
  (fn [request]
    (let [query (get-in request [:body-params :query])]
      (if-let [err (validate-search-query query)]
        err
        {:status 200
         :body   {:requests (sr/search service-request-port (str/trim query))}}))))
