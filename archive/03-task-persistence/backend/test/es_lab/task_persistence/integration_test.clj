(ns es-lab.task-persistence.integration-test
  (:require [babashka.http-client :as http]
            [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [es-lab.task-persistence.routes :as routes]
            [es-lab.task-persistence.test-support :as ts]
            [jsonista.core :as json]
            [ring.adapter.jetty :as jetty]))

(def ^:dynamic *base-url* nil)

(defn with-server [f]
  (let [ctx    (ts/make-ports)
        server (jetty/run-jetty (routes/make-router ctx)
                                {:port 0 :join? false})]
    (try
      (let [port (-> server .getConnectors first .getLocalPort)]
        (binding [*base-url* (str "http://localhost:" port)]
          (f)))
      (finally
        (.stop server)))))

(use-fixtures :once with-server)

(defn- post-json [path body]
  (http/post (str *base-url* path)
             {:headers {"content-type" "application/json"}
              :body    (json/write-value-as-string body)
              :throw   false}))

(defn- get-path [path]
  (http/get (str *base-url* path)))

(defn- parse-body [resp]
  (json/read-value (:body resp) json/keyword-keys-object-mapper))

(deftest health-returns-ok
  ;; QA-01-01
  (let [resp (get-path "/health")]
    (is (= 200 (:status resp)))
    (is (= {:status "ok"} (parse-body resp)))))

(deftest openapi-json-accessible
  ;; QA-02-01
  (let [resp (get-path "/openapi.json")]
    (is (= 200 (:status resp)))
    (is (string/starts-with? (:openapi (parse-body resp)) "3."))))

(deftest openapi-submit-endpoint-has-required-fields
  ;; QA-02-02
  (let [spec  (parse-body (get-path "/openapi.json"))
        body  (get-in spec [:paths
                            (keyword "/api/commands/submit-service-request")
                            :post :requestBody :content
                            (keyword "application/json") :schema])
        req   (set (:required body))]
    (is (= #{"title" "description"} req))))

(deftest openapi-submit-endpoint-has-non-blank-example
  ;; QA-02-03
  (let [spec    (parse-body (get-path "/openapi.json"))
        example (get-in spec [:paths
                              (keyword "/api/commands/submit-service-request")
                              :post :requestBody :content
                              (keyword "application/json") :example])]
    (is (not (clojure.string/blank? (str (:title example)))))
    (is (not (clojure.string/blank? (str (:description example)))))))

(deftest openapi-contains-list-endpoint
  ;; QA-02-04
  (let [spec (parse-body (get-path "/openapi.json"))]
    (is (contains? (:paths spec) (keyword "/api/queries/list-service-requests")))))

(deftest openapi-search-endpoint-has-required-query-field
  ;; QA-02-05
  (let [spec (parse-body (get-path "/openapi.json"))
        body (get-in spec [:paths
                           (keyword "/api/queries/search-service-requests")
                           :post :requestBody :content
                           (keyword "application/json") :schema])
        req  (set (:required body))]
    (is (= #{"query"} req))))

(deftest openapi-search-endpoint-has-non-blank-example
  ;; QA-02-06
  (let [spec    (parse-body (get-path "/openapi.json"))
        example (get-in spec [:paths
                              (keyword "/api/queries/search-service-requests")
                              :post :requestBody :content
                              (keyword "application/json") :example])]
    (is (not (clojure.string/blank? (str (:query example)))))))

(deftest swagger-ui-accessible
  ;; QA-02-07
  (let [resp (get-path "/swagger-ui")]
    (is (= 200 (:status resp)))))

(deftest submit-returns-201-over-http
  ;; AC-04-01 (via HTTP layer)
  (let [resp (post-json "/api/commands/submit-service-request"
                        {:title "T" :description "D"})]
    (is (= 201 (:status resp)))))

(deftest submit-rejects-blank-title-over-http
  ;; AC-04-04 (via HTTP layer)
  (doseq [body [{:description "D"} {:title "" :description "D"}]]
    (let [resp (post-json "/api/commands/submit-service-request" body)]
      (is (= 422 (:status resp)))
      (is (contains? (parse-body resp) :error)))))

(deftest submit-rejects-blank-description-over-http
  ;; AC-04-05 (via HTTP layer)
  (doseq [body [{:title "T"} {:title "T" :description ""}]]
    (let [resp (post-json "/api/commands/submit-service-request" body)]
      (is (= 422 (:status resp)))
      (is (contains? (parse-body resp) :error)))))

(deftest list-returns-200-over-http
  ;; AC-04-06 (status + requests key)
  (let [resp (post-json "/api/queries/list-service-requests" {})]
    (is (= 200 (:status resp)))
    (is (vector? (:requests (parse-body resp))))))

(deftest list-element-has-exactly-seven-fields
  ;; AC-04-06 (field shape)
  (post-json "/api/commands/submit-service-request"
             {:title "Shape check" :description "Verifying list element fields"})
  (let [resp     (post-json "/api/queries/list-service-requests" {})
        requests (:requests (parse-body resp))]
    (is (seq requests))
    (doseq [r requests]
      (is (= #{:request_id :title :description :status :submitted_by :created_at :updated_at}
             (set (keys r)))))))
