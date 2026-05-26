(ns registry.server
  (:require [clojure.string                   :as str]
            [ring.adapter.jetty               :refer [run-jetty]]
            [ring.middleware.params           :refer [wrap-params]]
            [muuntaja.core                    :as m]
            [reitit.openapi                   :as openapi]
            [reitit.ring                      :as reitit]
            [reitit.ring.middleware.muuntaja  :as muuntaja]
            [reitit.swagger-ui                :as swagger-ui]
            [jsonista.core                    :as json]
            [registry.registration.api.routes :as routes]))

(defn- json-response [body status]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string body)})

;; Auth is intentionally simplified for this lab: any Bearer token is accepted and
;; identity is hard-coded. Role enforcement (Applicant / Examiner / Registrar) and
;; ownership checks (caller must be the original applicant) are parked — a production
;; implementation must enforce these via a real identity provider.
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

(defn build-router [event-store register-port address-validator]
  (reitit/ring-handler
   (reitit/router
    [(routes/documentation-routes)
     (routes/registration-routes event-store register-port address-validator)]
    {:data {:muuntaja   m/instance
            :middleware [muuntaja/format-middleware]}
     :plugins [openapi/openapi-feature]})
   (reitit/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui"
                                           :url  "/openapi.json"})
    (reitit/create-default-handler
     {:not-found          (constantly (json-response {:error "Not found"} 404))
      :method-not-allowed (constantly (json-response {:error "Method not allowed"} 405))}))))

(defn build-app [event-store register-port address-validator]
  (let [api (-> (build-router event-store register-port address-validator)
                wrap-identity
                wrap-params
                wrap-correlation-id
                wrap-exception)]
    (fn [request]
      (if (= "/health" (:uri request))
        {:status 200 :body "ok"}
        (api request)))))

(defn start! [{:keys [port event-store register-port address-validator]
               :or   {port 8080}}]
  (println (str "Starting registry server on port " port))
  (run-jetty (build-app event-store register-port address-validator)
             {:port port :join? false}))

(defn stop! [server]
  (println "Stopping registry server")
  (.stop server))
