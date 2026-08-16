(ns registry.registration.api.routes
  (:require [registry.registration.api.handlers :as handlers]
            [reitit.openapi                    :as openapi]))

(def ^:private error-schema
  {:type       "object"
   :required   ["error"]
   :properties {:error {:type "string"}}})

(def ^:private ok-schema
  {:type       "object"
   :required   ["ok"]
   :properties {:ok {:type "boolean"}}})

(def ^:private application-created-schema
  {:type       "object"
   :required   ["application-id"]
   :properties {:application-id {:type "string" :format "uuid"}}})

(def ^:private proposed-director-schema
  {:type       "object"
   :required   ["id" "name" "natural-person" "identity-verified"]
   :properties {:id                {:type "string"}
                :name              {:type "string" :minLength 1}
                :natural-person    {:type "boolean"}
                :identity-verified {:type "boolean"}}})

(def ^:private registered-office-address-schema
  {:type       "object"
   :required   ["address-line-1" "city" "country"]
   :properties {:address-line-1 {:type "string" :minLength 1}
                :city           {:type "string" :minLength 1}
                :country        {:type "string" :minLength 1}}})

(def ^:private submit-application-schema
  {:type       "object"
   :required   ["company-name" "proposed-directors" "registered-office-address"]
   :properties {:company-name              {:type "string" :minLength 1}
                :proposed-directors        {:type     "array"
                                            :minItems 2
                                            :items    proposed-director-schema}
                :registered-office-address registered-office-address-schema}})

(def ^:private reject-application-schema
  {:type       "object"
   :required   ["rejection-reason"]
   :properties {:rejection-reason {:type "string" :minLength 1}}})

(def ^:private company-schema
  {:type       "object"
   :required   ["registration-number" "company-name" "registered-at"]
   :properties {:registration-number {:type "string"}
                :company-name        {:type "string"}
                :registered-at       {:type "string" :format "date-time"}}})

(def ^:private company-search-schema
  {:type       "object"
   :required   ["companies"]
   :properties {:companies {:type "array" :items company-schema}}})

(def ^:private empty-body
  {:content {"application/json"
             {:schema  {:type "object"}
              :example {}}}})

(def ^:private submit-application-example
  {:company-name "Acme Registry Ltd"
   :proposed-directors
   [{:id "director-1" :name "Jane Smith" :natural-person true :identity-verified true}
    {:id "director-2" :name "Alice Jones" :natural-person true :identity-verified true}]
   :registered-office-address
   {:address-line-1 "1 Main Street"
    :city           "Dublin"
    :country        "IE"}})

(def ^:private submit-application-body
  {:required true
   :content  {"application/json"
              {:schema  submit-application-schema
               :example submit-application-example}}})

(def ^:private reject-application-body
  {:required true
   :content  {"application/json"
              {:schema  reject-application-schema
               :example {:rejection-reason "Company name is misleading"}}}})

(def ^:private created-response
  {:description "Registration application created"
   :content     {"application/json" {:schema application-created-schema}}})

(def ^:private ok-response
  {:description "Command accepted"
   :content     {"application/json" {:schema ok-schema}}})

(def ^:private validation-error-response
  {:description "Validation or state-machine error"
   :content     {"application/json" {:schema error-schema}}})

(def ^:private unauthorised-response
  {:description "Missing bearer token"
   :content     {"application/json" {:schema error-schema}}})

(def ^:private not-found-response
  {:description "Resource not found"
   :content     {"application/json" {:schema error-schema}}})

(defn documentation-routes []
  [["/openapi.json"
    {:get {:no-doc  true
           :openapi {:openapi    "3.0.3"
                     :info       {:title   "legal-facts-registry"
                                  :version "0.1.0"}
                     :components {:securitySchemes
                                  {:bearerAuth {:type         "http"
                                                :scheme       "bearer"
                                                :bearerFormat "opaque"}}}}
           :handler (openapi/create-openapi-handler)}}]
   ["/health"
    {:get {:summary "Health check"
           :tags    ["ops"]
           :openapi {:responses {200 {:description "Service is healthy"}}}
           :handler (constantly {:status 200 :body "ok"})}}]])

(defn registration-routes [event-store register-port address-validator]
  ["/api/v1"
   ["/registration-applications"
    {:post {:summary "Create a new draft registration application"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody empty-body
                      :responses   {201 created-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/create-registration-application event-store)}}]

   ["/registration-applications/:id/submit"
    {:post {:summary "Submit a draft registration application"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody submit-application-body
                      :responses   {200 ok-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/submit-registration-application event-store)}}]

   ["/registration-applications/:id/begin-examination"
    {:post {:summary "Begin examination of a submitted application"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody empty-body
                      :responses   {200 ok-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/begin-examination event-store)}}]

   ["/registration-applications/:id/approve"
    {:post {:summary "Approve a registration application and register the company"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody empty-body
                      :responses   {200 ok-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/approve-registration-application event-store address-validator)}}]

   ["/registration-applications/:id/reject"
    {:post {:summary "Reject a registration application"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody reject-application-body
                      :responses   {200 ok-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/reject-registration-application event-store)}}]

   ["/registration-applications/:id/withdraw"
    {:post {:summary "Withdraw a registration application"
            :tags    ["registration-applications"]
            :openapi {:security    [{:bearerAuth []}]
                      :requestBody empty-body
                      :responses   {200 ok-response
                                    401 unauthorised-response
                                    422 validation-error-response}}
            :handler (handlers/withdraw-registration-application event-store)}}]

   ["/register/:registration-number"
    {:get {:summary "Look up a registered company"
           :tags    ["register"]
           :openapi {:security  [{:bearerAuth []}]
                     :responses {200 {:description "Registered company"
                                      :content     {"application/json" {:schema company-schema}}}
                                 401 unauthorised-response
                                 404 not-found-response}}
           :handler (handlers/get-company register-port)}}]

   ["/register"
    {:get {:summary "Search the register by company name"
           :tags    ["register"]
           :openapi {:security   [{:bearerAuth []}]
                     :parameters [{:in          "query"
                                   :name        "name"
                                   :description "Company name fragment"
                                   :schema      {:type "string"}}]
                     :responses  {200 {:description "Matching registered companies"
                                       :content     {"application/json" {:schema company-search-schema}}}
                                  401 unauthorised-response}}
           :handler (handlers/search-companies register-port)}}]])
