(ns es-lab.task-persistence.integration-test
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is use-fixtures]]
            [es-lab.task-persistence.routes :as routes]
            [es-lab.task-persistence.test-support :as support]
            [jsonista.core :as json]
            [ring.adapter.jetty :as jetty]))

(def mapper (json/object-mapper {:decode-key-fn true}))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(def ^:dynamic *port* nil)

(defn server-fixture [f]
  (let [port   (free-port)
        ctx    (support/make-ports)
        server (jetty/run-jetty (routes/make-router ctx) {:port port :join? false})]
    (binding [*port* port]
      (try (f) (finally (.stop server))))))

(use-fixtures :each server-fixture)

(defn- url [path] (str "http://localhost:" *port* path))

(defn- post-json [path body]
  (http/post (url path)
             {:body    (json/write-value-as-string body mapper)
              :headers {"content-type" "application/json"
                        "accept"       "application/json"}
              :throw   false}))

(deftest health-returns-200
  (is (= 200 (:status (http/get (url "/health"))))))

(deftest submit-returns-201
  (let [resp (post-json "/api/commands/submit-service-request"
                        {:title "Fix door" :description "Room 101"})]
    (is (= 201 (:status resp)))))

(deftest submit-response-has-request-id
  (let [resp (post-json "/api/commands/submit-service-request"
                        {:title "Fix door" :description "Room 101"})
        body (json/read-value (:body resp) mapper)]
    (is (= 201 (:status resp)))
    (is (string? (:request_id body)))
    (is (= "Fix door" (:title body)))
    (is (= "submitted" (:status body)))))

(deftest submit-uses-x-user-id-header
  (let [resp (http/post (url "/api/commands/submit-service-request")
                        {:body    (json/write-value-as-string {:title "T" :description "D"} mapper)
                         :headers {"content-type" "application/json"
                                   "x-user-id"    "alice"}
                         :throw   false})
        body (json/read-value (:body resp) mapper)]
    (is (= 201 (:status resp)))
    (is (= "alice" (:submitted_by body)))))

(deftest submit-returns-422-on-blank-title
  (is (= 422 (:status (post-json "/api/commands/submit-service-request"
                                 {:title "" :description "Details"})))))

(deftest list-returns-200
  (is (= 200 (:status (post-json "/api/queries/list-service-requests" {})))))

(deftest list-response-has-requests-key
  (let [body (-> (post-json "/api/queries/list-service-requests" {})
                 :body
                 (json/read-value mapper))]
    (is (vector? (:requests body)))))

(deftest submitted-request-appears-in-list
  (post-json "/api/commands/submit-service-request"
             {:title "Roundtrip test" :description "Should appear in list"})
  (let [requests (-> (post-json "/api/queries/list-service-requests" {})
                     :body
                     (json/read-value mapper)
                     :requests)]
    (is (some #(= "Roundtrip test" (:title %)) requests))))

(deftest list-returns-most-recent-request-first
  (post-json "/api/commands/submit-service-request"
             {:title "Older request" :description "Submitted first"})
  (post-json "/api/commands/submit-service-request"
             {:title "Newer request" :description "Submitted second"})
  (let [requests (-> (post-json "/api/queries/list-service-requests" {})
                     :body
                     (json/read-value mapper)
                     :requests)]
    (is (= "Newer request" (:title (first requests))))))

(deftest search-returns-matching-request
  (post-json "/api/commands/submit-service-request"
             {:title "Searchable printer issue" :description "Toner tray stuck"})
  (let [requests (-> (post-json "/api/queries/search-service-requests" {:query "printer"})
                     :body
                     (json/read-value mapper)
                     :requests)]
    (is (some #(= "Searchable printer issue" (:title %)) requests))))

(deftest search-returns-empty-list-when-nothing-matches
  (let [requests (-> (post-json "/api/queries/search-service-requests" {:query "nonexistent"})
                     :body
                     (json/read-value mapper)
                     :requests)]
    (is (= [] requests))))

(deftest search-returns-422-on-blank-query
  (is (= 422 (:status (post-json "/api/queries/search-service-requests" {:query " "}))))
  (is (= 422 (:status (post-json "/api/queries/search-service-requests" {})))))

(deftest openapi-returns-200
  (is (= 200 (:status (http/get (url "/openapi.json"))))))

(deftest openapi-documents-command-request-body
  (let [body (-> (http/get (url "/openapi.json"))
                 :body
                 (json/read-value mapper))]
    (is (= ["title" "description"]
           (get-in body [:paths (keyword "/api/commands/submit-service-request") :post :requestBody
                         :content :application/json :schema :required])))))

(deftest openapi-documents-command-request-example
  (let [body (-> (http/get (url "/openapi.json"))
                 :body
                 (json/read-value mapper))]
    (is (= {:title       "Broken printer"
            :description "Paper jam on floor 2"}
           (get-in body [:paths (keyword "/api/commands/submit-service-request") :post :requestBody
                         :content :application/json :example])))))

(deftest openapi-documents-search-request-body
  (let [body (-> (http/get (url "/openapi.json"))
                 :body
                 (json/read-value mapper))]
    (is (= ["query"]
           (get-in body [:paths (keyword "/api/queries/search-service-requests") :post :requestBody
                         :content :application/json :schema :required])))))

(deftest openapi-documents-search-request-example
  (let [body (-> (http/get (url "/openapi.json"))
                 :body
                 (json/read-value mapper))]
    (is (= {:query "printer"}
           (get-in body [:paths (keyword "/api/queries/search-service-requests") :post :requestBody
                         :content :application/json :example])))))

(deftest swagger-ui-returns-200
  (is (= 200 (:status (http/get (url "/swagger-ui"))))))
