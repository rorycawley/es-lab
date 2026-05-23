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

(def ^:private json-headers {"Content-Type" "application/json"})

(defn- post [path body]
  (http/post (str "http://localhost:8080" path)
             {:headers json-headers
              :body    (json/generate-string body)}))

(defn- post-via-proxy [path body]
  (http/post (str "http://localhost:4200" path)
             {:headers json-headers
              :body    (json/generate-string body)}))

(deftest frontend-returns-200
  (is (= 200 (:status (http/get "http://localhost:4200/")))))

(deftest frontend-content-type-is-html
  (let [ct (get-in (http/get "http://localhost:4200/") [:headers "content-type"])]
    (is (str/starts-with? ct "text/html"))))

(deftest frontend-body-contains-app-root
  (is (str/includes? (:body (http/get "http://localhost:4200/")) "<app-root")))

(deftest backend-health-directly-returns-200
  (is (= 200 (:status (http/get "http://localhost:8080/health")))))

(deftest backend-health-directly-is-ok
  (let [body (-> (http/get "http://localhost:8080/health") :body (json/parse-string true))]
    (is (= "ok" (:status body)))))

(deftest submit-command-returns-200
  (let [resp (post "/api/commands/submit-service-request"
                   {:title "Broken window" :description "Window on 2nd floor is cracked"})]
    (is (= 200 (:status resp)))))

(deftest submit-command-returns-request-id
  (let [body (-> (post "/api/commands/submit-service-request"
                       {:title "Leaking tap" :description "Kitchen tap drips constantly"})
                 :body
                 (json/parse-string true))]
    (is (string? (:request_id body)))))

(deftest list-query-returns-200
  (is (= 200 (:status (post "/api/queries/list-service-requests" {})))))

(deftest list-query-returns-requests-key
  (let [body (-> (post "/api/queries/list-service-requests" {}) :body (json/parse-string true))]
    (is (vector? (:requests body)))))

(deftest submit-then-list-roundtrip
  (post "/api/commands/submit-service-request"
        {:title "Noisy boiler" :description "Boiler in basement makes loud noise at night"})
  (let [requests (-> (post "/api/queries/list-service-requests" {}) :body (json/parse-string true) :requests)]
    (is (some #(= "Noisy boiler" (:title %)) requests))))

(deftest submit-via-proxy-returns-200
  (is (= 200 (:status (post-via-proxy "/api/commands/submit-service-request"
                                      {:title "Flickering light" :description "Light in corridor flickers"})))))

(deftest list-via-proxy-returns-200
  (is (= 200 (:status (post-via-proxy "/api/queries/list-service-requests" {})))))
