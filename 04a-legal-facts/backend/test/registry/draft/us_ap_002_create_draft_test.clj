(ns registry.draft.us-ap-002-create-draft-test
  (:require [clojure.test         :refer [deftest is use-fixtures]]
            [babashka.http-client :as http]
            [jsonista.core        :as json]
            [registry.server      :as server]))

(def ^:dynamic *base-url* nil)

(defn server-fixture [test-fn]
  (let [srv  (server/start! {:port 0})
        port (-> srv .getConnectors first .getLocalPort)]
    (binding [*base-url* (str "http://localhost:" port)]
      (try (test-fn)
           (finally (server/stop! srv))))))

(use-fixtures :once server-fixture)

(defn post [path body]
  (http/post (str *base-url* path)
             {:headers {"content-type"  "application/json"
                        "authorization" "Bearer test-token"}
              :body    (json/write-value-as-string body)
              :throw   false}))

(defn get-req [path]
  (http/get (str *base-url* path)
            {:headers {"authorization" "Bearer test-token"}
             :throw   false}))

(defn parse-body [response]
  (json/read-value (:body response) (json/object-mapper {:decode-key-fn keyword})))

(deftest AC-AP-002-001-create-draft-active-state
  (let [response (post "/api/v1/company-registration-drafts" {})
        body     (parse-body response)
        location (get-in response [:headers "location"])
        get-resp (get-req location)
        get-body (parse-body get-resp)]
    (is (= 201      (:status response)))
    (is (some?      (:draft-id body)))
    (is (= "active" (:state body)))
    (is (= (str "/api/v1/company-registration-drafts/" (:draft-id body))
           location))
    (is (= 200      (:status get-resp)))
    (is (= (:draft-id body) (:draft-id get-body)))
    (is (= "active" (:state get-body)))))
