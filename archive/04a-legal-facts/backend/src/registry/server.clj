(ns registry.server
  (:require [clojure.string                  :as str]
            [ring.adapter.jetty              :refer [run-jetty]]
            [ring.middleware.params          :refer [wrap-params]]
            [muuntaja.core                   :as m]
            [reitit.openapi                  :as openapi]
            [reitit.ring                     :as reitit]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.swagger-ui               :as swagger-ui]
            [jsonista.core                   :as json]
            [registry.draft.api.routes       :as routes]))

(defn- json-response [body status]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string body)})

(defn wrap-identity [handler]
  (fn [request]
    (let [api-request? (str/starts-with? (:uri request) "/api/")
          token        (get-in request [:headers "authorization"])]
      (if (and api-request? (nil? token))
        (json-response {:error "Unauthorised"} 401)
        (handler (assoc request :identity {:user-id "user-from-token"}))))))

(defn wrap-correlation-id [handler]
  (fn [request]
    (let [cid  (or (get-in request [:headers "x-correlation-id"])
                   (str (java.util.UUID/randomUUID)))
          resp (handler (assoc request :correlation-id cid))]
      (assoc-in resp [:headers "x-correlation-id"] cid))))

(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Unhandled exception:" (.getMessage e))
        (json-response {:error "Internal server error"} 500)))))

(defn build-router []
  (reitit/ring-handler
   (reitit/router
    [(routes/documentation-routes)
     (routes/draft-routes)]
    {:data    {:muuntaja m/instance :middleware [muuntaja/format-middleware]}
     :plugins [openapi/openapi-feature]})
   (reitit/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui" :url "/openapi.json"})
    (reitit/create-default-handler
     {:not-found          (constantly (json-response {:error "Not found"} 404))
      :method-not-allowed (constantly (json-response {:error "Method not allowed"} 405))}))))

(defn build-app []
  (let [api (-> (build-router)
                wrap-identity
                wrap-params
                wrap-correlation-id
                wrap-exception)]
    (fn [request]
      (if (= "/health" (:uri request))
        {:status 200 :body "ok"}
        (api request)))))

(defn start! [{:keys [port] :or {port 8080}}]
  (println (str "Starting registry server on port " port))
  (run-jetty (build-app) {:port port :join? false}))

(defn stop! [server]
  (println "Stopping registry server")
  (.stop server))
