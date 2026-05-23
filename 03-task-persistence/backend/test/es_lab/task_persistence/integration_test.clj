(ns es-lab.task-persistence.integration-test
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
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

(use-fixtures :once server-fixture)

(defn- url [path] (str "http://localhost:" *port* path))

(defn- post-json [path body]
  (http/post (url path)
             {:body    (json/write-value-as-string body mapper)
              :headers {"content-type" "application/json"
                        "accept"       "application/json"}
              :throw   false}))

(deftest health-returns-200
  (is (= 200 (:status (http/get (url "/health"))))))

(deftest submit-returns-200
  (let [resp (post-json "/api/commands/submit-service-request"
                        {:title "Fix door" :description "Room 101"})]
    (is (= 200 (:status resp)))))

(deftest submit-response-has-request-id
  (let [body (-> (post-json "/api/commands/submit-service-request"
                             {:title "Fix door" :description "Room 101"})
                 :body
                 (json/read-value mapper))]
    (is (string? (:request_id body)))
    (is (= "Fix door" (:title body)))
    (is (= "submitted" (:status body)))))

(deftest submit-uses-x-user-id-header
  (let [body (-> (http/post (url "/api/commands/submit-service-request")
                            {:body    (json/write-value-as-string {:title "T" :description "D"} mapper)
                             :headers {"content-type" "application/json"
                                       "x-user-id"    "alice"}
                             :throw   false})
                 :body
                 (json/read-value mapper))]
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

(deftest openapi-returns-200
  (is (= 200 (:status (http/get (url "/openapi.json"))))))

(deftest swagger-ui-returns-200
  (is (= 200 (:status (http/get (url "/swagger-ui"))))))
