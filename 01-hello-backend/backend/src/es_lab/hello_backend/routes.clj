(ns es-lab.hello-backend.routes
  (:require [muuntaja.core :as m]
            [reitit.openapi :as openapi]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.swagger-ui :as swagger-ui]))

(defn health-handler [_]
  {:status 200
   :body   {:status "ok" :version "0.1.0"}})

(def router
  (ring/ring-handler
   (ring/router
    [["/openapi.json" {:get {:no-doc  true
                             :openapi {:openapi "3.0.3"
                                       :info {:title   "hello-backend"
                                              :version "0.1.0"}}
                             :handler (openapi/create-openapi-handler)}}]
     ["/health" {:get {:summary   "Health check"
                       :tags      ["health"]
                       :responses {200 {:description "Service is healthy"}}
                       :handler   health-handler}}]]
    {:data    {:muuntaja   m/instance
               :middleware [muuntaja/format-middleware]}
     :plugins [openapi/openapi-feature]})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui"
                                           :url  "/openapi.json"})
    (ring/create-default-handler))))
