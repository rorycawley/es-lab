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

(def ^:private service-request-schema
  {:type       "object"
   :properties {:request_id   {:type "string" :format "uuid"}
                :submitted_by {:type "string"}
                :title        {:type "string"}
                :description  {:type "string"}
                :status       {:type "string" :enum ["submitted"]}
                :created_at   {:type "string" :format "date-time"}
                :updated_at   {:type "string" :format "date-time"}}})

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
      {:post {:summary      "Submit a service request"
              :tags         ["service-requests"]
              :request-body {:required true
                             :content  {"application/json"
                                        {:schema {:type       "object"
                                                  :required   ["title" "description"]
                                                  :properties {:title       {:type "string" :minLength 1}
                                                               :description {:type "string" :minLength 1}}}}}}
              :responses    {200 {:description "Request submitted"
                                  :content     {"application/json" {:schema service-request-schema}}}
                             422 {:description "Validation error"
                                  :content     {"application/json"
                                                {:schema {:type       "object"
                                                          :properties {:error {:type "string"}}}}}}}
              :handler      (commands/submit-service-request-handler ctx)}}]
     ["/api/queries/list-service-requests"
      {:post {:summary      "List all service requests"
              :tags         ["service-requests"]
              :request-body {:content {"application/json" {:schema {:type "object"}}}}
              :responses    {200 {:description "List of requests"
                                  :content     {"application/json"
                                                {:schema {:type       "object"
                                                          :properties {:requests {:type  "array"
                                                                                  :items service-request-schema}}}}}}}
              :handler      (queries/list-service-requests-handler ctx)}}]]
    {:data    {:muuntaja   m/instance
               :middleware [muuntaja/format-middleware]}
     :plugins [openapi/openapi-feature]})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui"
                                           :url  "/openapi.json"})
    (ring/create-default-handler))))
