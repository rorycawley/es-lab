(ns es-lab.task-persistence.routes
  (:require [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.service-requests.queries :as queries]
            [muuntaja.core :as m]
            [reitit.openapi :as openapi]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.swagger-ui :as swagger-ui]))

(defn- health-handler [_]
  {:status 200 :body {:status "ok" :version "0.1.0"}})

(defn make-router [ctx]
  (ring/ring-handler
   (ring/router
    [["/openapi.json" {:get {:no-doc  true
                             :openapi {:openapi "3.0.3"
                                       :info {:title   "task-persistence"
                                              :version "0.1.0"}}
                             :handler (openapi/create-openapi-handler)}}]
     ["/health" {:get {:summary   "Health check"
                       :tags      ["ops"]
                       :responses {200 {:description "Service is healthy"}}
                       :handler   health-handler}}]
     ["/api/commands/submit-service-request"
      {:post {:summary   "Submit a service request"
              :tags      ["service-requests"]
              :responses {200 {:description "Request submitted"}
                          422 {:description "Validation error"}}
              :handler   (commands/submit-service-request-handler ctx)}}]
     ["/api/queries/list-service-requests"
      {:post {:summary   "List all service requests"
              :tags      ["service-requests"]
              :responses {200 {:description "List of requests"}}
              :handler   (queries/list-service-requests-handler ctx)}}]]
    {:data    {:muuntaja   m/instance
               :middleware [muuntaja/format-middleware]}
     :plugins [openapi/openapi-feature]})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui"
                                           :url  "/openapi.json"})
    (ring/create-default-handler))))
