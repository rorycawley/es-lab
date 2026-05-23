(ns es-lab.hello-backend.integration-test
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [es-lab.hello-backend.routes :refer [router]]
            [jsonista.core :as json]
            [ring.adapter.jetty :as jetty]))

(def mapper (json/object-mapper {:decode-key-fn true}))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(def ^:dynamic *port* nil)

(defn server-fixture [f]
  (let [port   (free-port)
        server (jetty/run-jetty router {:port port :join? false})]
    (binding [*port* port]
      (try (f) (finally (.stop server))))))

(use-fixtures :once server-fixture)

(defn request-health []
  (http/get (str "http://localhost:" *port* "/health")
            {:headers {"Accept" "application/json"}}))

(defn request-openapi []
  (http/get (str "http://localhost:" *port* "/openapi.json")
            {:headers {"Accept" "application/json"}}))

(deftest health-returns-200
  (is (= 200 (:status (request-health)))))

(deftest health-content-type-is-json
  (let [content-type (get-in (request-health) [:headers "content-type"])]
    (is (str/starts-with? content-type "application/json"))))

(deftest health-body-has-status-ok
  (let [body (-> (request-health) :body (json/read-value mapper))]
    (is (= "ok" (:status body)))))

(deftest health-body-has-version
  (let [body (-> (request-health) :body (json/read-value mapper))]
    (is (string? (:version body)))))

(deftest openapi-returns-200
  (is (= 200 (:status (request-openapi)))))

(deftest openapi-content-type-is-json
  (let [content-type (get-in (request-openapi) [:headers "content-type"])]
    (is (str/starts-with? content-type "application/json"))))

(deftest openapi-body-has-health-path
  (let [body (-> (request-openapi) :body (json/read-value mapper))]
    (is (get-in body [:paths (keyword "/health")]))))

(deftest openapi-version-is-swagger-ui-compatible
  (let [body (-> (request-openapi) :body (json/read-value mapper))]
    (is (re-matches #"3\.0\.\d+" (:openapi body)))))

(deftest swagger-ui-returns-200
  (is (= 200 (:status (http/get (str "http://localhost:" *port* "/swagger-ui"))))))
