(ns acceptance.health-test
  (:require [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(defn stack-fixture [f]
  (shell "docker compose up -d --build --wait")
  (try (f)
       (finally (shell "docker compose down"))))

(use-fixtures :once stack-fixture)

(deftest health-returns-200
  (let [response (http/get "http://localhost:8080/health")]
    (is (= 200 (:status response)))))

(deftest health-content-type-is-json
  (let [response (http/get "http://localhost:8080/health")
        ct       (get-in response [:headers "content-type"])]
    (is (str/starts-with? ct "application/json"))))

(deftest health-body-has-status-ok
  (let [body (-> (http/get "http://localhost:8080/health") :body (json/parse-string true))]
    (is (= "ok" (:status body)))))

(deftest health-body-has-version
  (let [body (-> (http/get "http://localhost:8080/health") :body (json/parse-string true))]
    (is (string? (:version body)))))

(deftest openapi-returns-200
  (let [response (http/get "http://localhost:8080/openapi.json")]
    (is (= 200 (:status response)))))

(deftest openapi-content-type-is-json
  (let [ct (get-in (http/get "http://localhost:8080/openapi.json") [:headers "content-type"])]
    (is (str/starts-with? ct "application/json"))))

(deftest openapi-body-has-health-path
  (let [body (-> (http/get "http://localhost:8080/openapi.json") :body (json/parse-string true))]
    (is (get-in body [:paths (keyword "/health")]))))

(deftest openapi-version-is-swagger-ui-compatible
  (let [body (-> (http/get "http://localhost:8080/openapi.json") :body (json/parse-string true))]
    (is (re-matches #"3\.0\.\d+" (:openapi body)))))

(deftest swagger-ui-returns-200
  (let [response (http/get "http://localhost:8080/swagger-ui")]
    (is (= 200 (:status response)))))
