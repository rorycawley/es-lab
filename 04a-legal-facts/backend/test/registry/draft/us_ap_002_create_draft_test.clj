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

(deftest AC-AP-002-001-create-draft-route-exists
  (let [response (post "/api/v1/company-registration-drafts" {})]
    (is (not= 404 (:status response)))))
