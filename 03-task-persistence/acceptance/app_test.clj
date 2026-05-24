(ns acceptance.app-test
  (:require [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private frontend "http://localhost:4200")
(def ^:private backend  "http://localhost:8080")

(defn- post-json
  ([base path body] (post-json base path body {}))
  ([base path body extra-headers]
   (http/post (str base path)
              {:headers (merge {"content-type" "application/json"
                                "accept"       "application/json"}
                               extra-headers)
               :body    (json/generate-string body)
               :throw   false})))

(defn- get-url [url]
  (http/get url {:throw false}))

(defn- parse [resp]
  (json/parse-string (:body resp) true))

(deftest compose-stack-is-healthy
  ;; AC-01-01
  (let [running (shell {:out :string} "docker" "compose" "ps" "--services" "--filter" "status=running")
        exited  (shell {:out :string} "docker" "compose" "ps" "--services" "--filter" "status=exited")]
    (is (str/includes? (:out running) "postgres"))
    (is (str/includes? (:out running) "backend"))
    (is (str/includes? (:out running) "frontend"))
    (is (str/includes? (:out exited) "migrate"))))

(deftest frontend-serves-html-with-app-root
  ;; AC-01-02
  (let [resp (get-url (str frontend "/"))]
    (is (= 200 (:status resp)))
    (is (str/starts-with?
          (get-in resp [:headers "content-type"] "")
          "text/html"))
    (is (str/includes? (:body resp) "<app-root"))))

(deftest stack-accepts-commands-after-compose-up
  ;; QA-01-02
  (let [resp (post-json backend "/api/commands/submit-service-request"
                        {:title "Readiness check" :description "Verifying stack is ready"})]
    (is (= 201 (:status resp)))))

(deftest submit-then-list-roundtrip
  ;; AC-04-07
  (let [title (str "Roundtrip " (random-uuid))
        sub   (post-json frontend "/api/commands/submit-service-request"
                         {:title title :description "E2E check"})]
    (is (= 201 (:status sub)))
    (let [listed (parse (post-json frontend "/api/queries/list-service-requests" {}))]
      (is (some #(= title (:title %)) (:requests listed))))))

(deftest submit-then-search-roundtrip
  ;; AC-08-05 (via full stack)
  (let [term  (str "searchterm-" (random-uuid))
        title (str "Request about " term)]
    (post-json frontend "/api/commands/submit-service-request"
               {:title title :description "Testing full-text search path"})
    (let [result (parse (post-json frontend "/api/queries/search-service-requests" {:query term}))]
      (is (some #(= title (:title %)) (:requests result))))))

(deftest proxy-returns-same-status-and-fields-as-direct-backend
  ;; AC-04-09
  (let [title       (str "Proxy-test " (random-uuid))
        via-proxy   (post-json frontend "/api/commands/submit-service-request"
                               {:title title :description "Via proxy"})
        via-direct  (post-json backend "/api/commands/submit-service-request"
                               {:title title :description "Via direct"})]
    (is (= (:status via-proxy) (:status via-direct)))
    (is (= (set (keys (parse via-proxy)))
           (set (keys (parse via-direct)))))))

(deftest dangerous-input-stored-and-returned-unchanged
  ;; QA-05-01
  (doseq [input ["'; DROP TABLE service_requests; --"
                 "<script>alert(1)</script>"]]
    (let [sub (post-json frontend "/api/commands/submit-service-request"
                         {:title input :description "Security check"})]
      (is (= 201 (:status sub)))
      (is (= input (:title (parse sub))))
      (let [listed (parse (post-json frontend "/api/queries/list-service-requests" {}))]
        (is (some #(= input (:title %)) (:requests listed)))))))

(deftest error-response-is-json-without-stack-trace
  ;; QA-05-02
  (let [resp (post-json frontend "/api/commands/submit-service-request"
                        {:title "" :description "D"})]
    (is (= 422 (:status resp)))
    (let [body (:body resp)]
      (is (map? (json/parse-string body)))
      (is (not (str/includes? body "Exception")))
      (is (not (str/includes? body "\tat ")))
      (is (not (str/includes? body "jdbc:")))
      (is (not (str/includes? body "password"))))))

(deftest data-survives-postgres-and-backend-restart
  ;; AC-05-01
  (let [title (str "Durability " (random-uuid))]
    (is (= 201 (:status (post-json frontend "/api/commands/submit-service-request"
                                   {:title title :description "Persistence test"}))))
    (shell "docker" "compose" "restart" "postgres" "backend")
    (shell "docker" "compose" "up" "-d" "--wait")
    (let [listed (parse (post-json frontend "/api/queries/list-service-requests" {}))]
      (is (some #(= title (:title %)) (:requests listed))))))
