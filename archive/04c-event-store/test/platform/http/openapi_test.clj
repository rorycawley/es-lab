(ns platform.http.openapi-test
  (:require [cheshire.core :as json]
            [cart.slice.add-product-item.adapter.in.http :as add-http]
            [cart.slice.add-product-item.port :as add-port]
            [cart.slice.view-cart.adapter.in.http :as view-http]
            [cart.slice.view-cart.port :as view-port]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [platform.http.router :as router])
  (:import [com.atlassian.oai.validator OpenApiInteractionValidator]
           [com.atlassian.oai.validator.model Request$Method
            SimpleRequest$Builder
            SimpleResponse$Builder]
           [com.atlassian.oai.validator.report SimpleValidationReportFormat]
           [io.swagger.v3.parser OpenAPIV3Parser]
           [io.swagger.v3.parser.core.models ParseOptions]))

(def contract-text
  (slurp (io/resource "openapi/cart-api.openapi.json")))

(def contract
  (json/parse-string contract-text))

(def business-paths
  #{"/commands/add-product-item"
    "/commands/remove-product-item"
    "/commands/confirm-cart"
    "/commands/cancel-cart"
    "/queries/view-cart"
    "/queries/review-cart-change-history"})

(def operational-paths #{"/health" "/ready" "/openapi.json"})

(def request-id "10000000-0000-0000-0000-000000000001")
(def cart-id "20000000-0000-0000-0000-000000000002")
(def product-id "30000000-0000-0000-0000-000000000003")
(def observation "v1.key.payload.signature")

(def first-add
  {"request-id" request-id
   "product-item" {"product-id" product-id "quantity" 2}})

(def existing-add
  (assoc first-add
         "cart-id" cart-id
         "cart-observation" observation))

(def existing-command
  {"request-id" request-id
   "cart-id" cart-id
   "cart-observation" observation})

(def query-request {"cart-id" cart-id})

(def valid-request-examples
  {"/commands/add-product-item" first-add
   "/commands/remove-product-item" (assoc existing-command
                                          "product-id" product-id
                                          "quantity" 1)
   "/commands/confirm-cart" existing-command
   "/commands/cancel-cart" existing-command
   "/queries/view-cart" query-request
   "/queries/review-cart-change-history" query-request})

(defn- parse-options []
  (doto (ParseOptions.)
    (.setResolve true)
    (.setResolveCombinators true)
    (.setValidateInternalRefs true)
    (.setValidateExternalRefs false)))

(defn- parse-contract []
  (.readContents (OpenAPIV3Parser.) contract-text nil (parse-options)))

(def openapi-validator
  (delay
    (-> (OpenApiInteractionValidator/createForInlineApiSpecification
         contract-text)
        (.withStrictOperationPathMatching)
        (.build))))

(defn- validator-request [path body]
  (-> (SimpleRequest$Builder/post path)
      (.withContentType "application/json")
      (.withBody (json/generate-string body))
      (.build)))

(defn- request-report [path body]
  (.validateRequest @openapi-validator (validator-request path body)))

(defn- validator-response [response]
  (cond-> (SimpleResponse$Builder/status (:status response))
    (get-in response [:headers "content-type"])
    (.withContentType (get-in response [:headers "content-type"]))
    (:body response)
    (.withBody (:body response))))

(defn- response-report [path method response]
  (.validateResponse @openapi-validator
                     path
                     (Request$Method/valueOf (str/upper-case (name method)))
                     (.build (validator-response response))))

(defn- report-text [report]
  (.apply (SimpleValidationReportFormat/getInstance) report))

(defn- assert-valid-report [label report]
  (is (not (.hasErrors report))
      (str label "\n" (report-text report))))

(defn- ring-request [handler method uri]
  (handler {:request-method method :uri uri}))

(deftest contract-defines-task-based-post-api
  (is (= "3.1.0" (get contract "openapi")))
  (is (every? #(contains? (get contract "paths") %)
              operational-paths))
  (is (= business-paths
         (set (filter #(or (.startsWith % "/commands/")
                           (.startsWith % "/queries/"))
                      (keys (get contract "paths"))))))
  (doseq [path business-paths]
    (is (= #{"post"} (set (keys (get-in contract ["paths" path])))))))

(deftest checked-in-contract-parses-with-swagger-parser
  (let [result (parse-contract)]
    (is (some? (.getOpenAPI result)))
    (is (= [] (vec (.getMessages result)))
        (str "OpenAPI parser messages: "
             (pr-str (vec (.getMessages result)))))))

(deftest every-business-request-body-has-a-valid-example
  (is (= business-paths (set (keys valid-request-examples))))
  (doseq [[path example] (sort-by key valid-request-examples)]
    (assert-valid-report (str "post " path)
                         (request-report path example)))
  (testing "both first-add and existing-cart variants conform"
    (assert-valid-report "first addition"
                         (request-report "/commands/add-product-item" first-add))
    (assert-valid-report "existing-cart addition"
                         (request-report "/commands/add-product-item" existing-add))))

(deftest invalid-business-request-examples-fail-contract-validation
  (doseq [[label path example]
          [["price is undeclared"
            "/commands/add-product-item"
            (assoc-in first-add ["product-item" "price"] 12.50)]
           ["quantity is outside the declared range"
            "/commands/add-product-item"
            (assoc-in first-add ["product-item" "quantity"] 0)]
           ["request identifier is not a UUID"
            "/commands/add-product-item"
            (assoc first-add "request-id" "not-a-uuid")]
           ["existing addition cannot omit its observation"
            "/commands/add-product-item"
            (assoc first-add "cart-id" cart-id)]
           ["query fields are closed"
            "/queries/view-cart"
            (assoc query-request "include-events" true)]]]
    (testing label
      (is (.hasErrors (request-report path example))
          (str "Expected OpenAPI validation errors for " (pr-str example))))))

(deftest operational-responses-are-live-and-conform-to-openapi
  (let [ready-handler     (router/handler)
        not-ready-handler (router/handler {:ready? (constantly false)})
        examples          {["/health" :get 200]
                           (ring-request ready-handler :get "/health")
                           ["/ready" :get 200]
                           (ring-request ready-handler :get "/ready")
                           ["/ready" :get 503]
                           (ring-request not-ready-handler :get "/ready")
                           ["/openapi.json" :get 200]
                           (ring-request ready-handler :get "/openapi.json")}]
    (is (= contract-text
           (:body (get examples ["/openapi.json" :get 200]))))
    (doseq [[[path method status] response] examples]
      (testing (str (name method) " " path " " status)
        (is (= status (:status response)))
        (assert-valid-report (str method " " path " response")
                             (response-report path method response))))))

(deftest command-response-contract-fixes-swr-008-outcomes
  (doseq [path (filter #(.startsWith % "/commands/") business-paths)]
    (is (= #{"200" "400" "409" "422" "500"}
           (set (keys (get-in contract ["paths" path "post" "responses"]))))))
  (is (= ["cart-closed"
          "insufficient-product-quantity"
          "cart-has-no-items"
          "product-quantity-limit-exceeded"]
         (get-in contract
                 ["components" "schemas" "RejectedResponse"
                  "properties" "code" "enum"]))))

(deftest request-and-response-objects-are-closed
  (doseq [[name schema] (get-in contract ["components" "schemas"])
          :when (= "object" (get schema "type"))]
    (testing name
      (is (false? (get schema "additionalProperties"))))))

(deftest api-records-core-contract-decisions
  (is (re-find #"trusted upstream"
               (get contract "x-deployment-constraint")))
  (is (= 1000
         (get-in contract
                 ["components" "schemas" "ProductItem"
                  "properties" "quantity" "maximum"])))
  (is (= 2
         (count (get-in contract
                        ["components" "schemas" "AddProductItemRequest"
                         "oneOf"]))))
  (is (false?
       (contains? (get-in contract
                          ["components" "schemas" "ProductItem" "properties"])
                  "price"))))

(defn- outcome-stub [result]
  (reify add-port/AddProductItem
    (add-product-item [_ _] result)))

(defn- view-outcome-stub [result]
  (reify view-port/ViewCart
    (view-cart [_ _] result)))

(deftest delivered-add-http-mappings-conform-to-every-declared-status
  (let [cart-result {:cart-id cart-id
                     :status "open"
                     :items [{:product-id product-id :quantity 2}]
                     :cart-observation observation}
        outcomes [{:outcome :success :result cart-result}
                  {:outcome :invalid :code :invalid-request
                   :field-errors [{:field "request-id" :code :invalid-uuid}]}
                  {:outcome :conflict :code :cart-changed
                   :next-action :view-cart-before-retrying}
                  {:outcome :rejected :code :cart-closed}]]
    (doseq [outcome outcomes
            :let [response ((add-http/handler (outcome-stub outcome))
                            {:body-params first-add})]]
      (assert-valid-report
       (str "add response " (:outcome outcome))
       (response-report "/commands/add-product-item" :post response)))
    (let [correlation-id "90000000-0000-0000-0000-000000000001"
          failing (reify add-port/AddProductItem
                    (add-product-item [_ _]
                      (throw (ex-info "storage unavailable" {}))))
          response ((add-http/handler
                     failing
                     {:correlation-id-fn #(java.util.UUID/fromString
                                           correlation-id)})
                    {:body-params first-add})]
      (assert-valid-report
       "add response unexpected failure"
       (response-report "/commands/add-product-item" :post response)))))

(deftest delivered-view-http-mappings-conform-to-every-declared-status
  (let [cart-result {:cart-id cart-id
                     :status "open"
                     :items [{:product-id product-id :quantity 2}]
                     :cart-observation observation}
        outcomes [{:outcome :success :result cart-result}
                  {:outcome :invalid :code :invalid-cart
                   :field-errors [{:field "cart-id" :code :invalid-cart}]}]]
    (doseq [outcome outcomes
            :let [response ((view-http/handler (view-outcome-stub outcome))
                            {:body-params query-request})]]
      (assert-valid-report
       (str "view response " (:outcome outcome))
       (response-report "/queries/view-cart" :post response)))
    (let [correlation-id "90000000-0000-0000-0000-000000000001"
          failing (reify view-port/ViewCart
                    (view-cart [_ _]
                      (throw (ex-info "projection unavailable" {}))))
          response ((view-http/handler
                     failing
                     {:correlation-id-fn #(java.util.UUID/fromString
                                           correlation-id)})
                    {:body-params query-request})]
      (assert-valid-report
       "view response unexpected failure"
       (response-report "/queries/view-cart" :post response)))))
