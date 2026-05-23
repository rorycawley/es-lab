(ns acceptance.app-test
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

(deftest frontend-returns-200
  (is (= 200 (:status (http/get "http://localhost:4200/")))))

(deftest frontend-content-type-is-html
  (let [ct (get-in (http/get "http://localhost:4200/") [:headers "content-type"])]
    (is (str/starts-with? ct "text/html"))))

(deftest frontend-body-contains-app-root
  (is (str/includes? (:body (http/get "http://localhost:4200/")) "<app-root")))

(deftest backend-health-via-proxy-returns-200
  (is (= 200 (:status (http/get "http://localhost:4200/api/health")))))

(deftest backend-health-via-proxy-is-ok
  (let [body (-> (http/get "http://localhost:4200/api/health") :body (json/parse-string true))]
    (is (= "ok" (:status body)))))

(deftest backend-health-directly-returns-200
  (is (= 200 (:status (http/get "http://localhost:8080/health")))))

(deftest backend-health-directly-is-ok
  (let [body (-> (http/get "http://localhost:8080/health") :body (json/parse-string true))]
    (is (= "ok" (:status body)))))

(deftest backend-health-directly-has-version
  (let [body (-> (http/get "http://localhost:8080/health") :body (json/parse-string true))]
    (is (string? (:version body)))))
