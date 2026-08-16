(ns platform.runtime.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [platform.runtime.config :as config]))

(def base-config
  {:store :memory
   :http {:host "127.0.0.1" :port 8080}
   :deployment {:trusted-upstream? false}
   :observation {:active-key-id "test"
                 :keys {"test" "configuration-test-signing-key"}}})

(deftest store-values-are-closed
  (is (= :memory (config/parse-store "memory")))
  (is (= :sqlite (config/parse-store "sqlite")))
  (is (= :postgres (config/parse-store "postgres")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"memory, sqlite or postgres"
                        (config/parse-store "mysql"))))

(deftest trust-boundary-is-enforced-for-wildcard-binds
  (doseq [host ["0.0.0.0" "::" "[::]"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"TRUSTED_UPSTREAM_ENFORCED=true"
                          (config/validate!
                           (assoc-in base-config [:http :host] host)))))
  (is (= "0.0.0.0"
         (get-in (config/validate!
                  (-> base-config
                      (assoc-in [:http :host] "0.0.0.0")
                      (assoc-in [:deployment :trusted-upstream?] true)))
                 [:http :host]))))

(deftest ports-and-postgres-configuration-are-validated
  (testing "port zero is valid for ephemeral test servers"
    (is (= 0 (:port (:http (config/validate!
                            (assoc-in base-config [:http :port] 0)))))))
  (doseq [port [-1 65536]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"PORT must be"
                          (config/validate!
                           (assoc-in base-config [:http :port] port)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"JDBC_URL or DATABASE_URL"
                        (config/validate!
                         (assoc base-config :store :postgres :db {})))))

(deftest resource-configuration-starts-in-local-memory-mode
  (let [loaded (config/read-config :memory)]
    (is (= :memory (:store loaded)))
    (is (= "127.0.0.1" (get-in loaded [:http :host])))
    (is (= 8080 (get-in loaded [:http :port])))
    (is (= "primary" (get-in loaded [:observation :active-key-id])))
    (is (nil? (:db loaded)))))
