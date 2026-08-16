(ns registry.draft.api.routes
  (:require [registry.draft.api.handlers :as handlers]
            [reitit.openapi              :as openapi]))

(def ^:private draft-created-schema
  {:type       "object"
   :required   ["draft-id"]
   :properties {:draft-id {:type "string" :format "uuid"}}})

(def ^:private empty-body
  {:content {"application/json"
             {:schema {:type "object"} :example {}}}})

(def ^:private created-response
  {:description "Draft created"
   :content     {"application/json" {:schema draft-created-schema}}})

(def ^:private unauthorised-response
  {:description "Missing bearer token"
   :content     {"application/json" {:schema {:type "object" :properties {:error {:type "string"}}}}}})

(defn documentation-routes []
  [["/openapi.json"
    {:get {:no-doc  true
           :openapi {:openapi    "3.0.3"
                     :info       {:title "company-registration-drafts" :version "0.1.0"}
                     :components {:securitySchemes
                                  {:bearerAuth {:type "http" :scheme "bearer" :bearerFormat "opaque"}}}}
           :handler (openapi/create-openapi-handler)}}]
   ["/health"
    {:get {:summary "Health check"
           :tags    ["ops"]
           :openapi {:responses {200 {:description "Service is healthy"}}}
           :handler (constantly {:status 200 :body "ok"})}}]])

(def ^:private draft-schema
  {:type       "object"
   :required   ["draft-id" "state"]
   :properties {:draft-id {:type "string" :format "uuid"}
                :state    {:type "string"}}})

(defn draft-routes []
  ["/api/v1"
   ["/company-registration-drafts"
    {:post {:summary "Create a new company registration draft"
            :tags    ["drafts"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody empty-body
                      :responses   {201 created-response
                                    401 unauthorised-response}}
            :handler handlers/create-draft}}]
   ["/company-registration-drafts/:id"
    {:get {:summary "Get a company registration draft"
           :tags    ["drafts"]
           :openapi {:security  [{:bearerAuth []}]
                     :responses {200 {:description "Draft found"
                                      :content     {"application/json" {:schema draft-schema}}}
                                 401 unauthorised-response
                                 404 {:description "Draft not found"}}}
           :handler handlers/get-draft}}]])
