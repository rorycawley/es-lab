; Rancher Desktop one-time setup required for Testcontainers to connect:
;   ~/.docker-java.properties   → api.version=1.44
;   ~/.testcontainers.properties → docker.host=unix:///Users/$USER/.rd/docker.sock
;                                  ryuk.disabled=true / checks.disable=true
; docker-java defaults to API 1.32; Rancher Desktop's daemon requires >=1.41.
; DOCKER_API_VERSION env var is NOT read by docker-java — the properties file is the fix.
; bb.edn test:backend supplies DOCKER_HOST / TESTCONTAINERS_* env vars automatically.
(ns es-lab.task-persistence.db.postgres-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.db.migrations :as migrations]
            [es-lab.task-persistence.db.postgres :as pg]
            [es-lab.task-persistence.service-requests.port :as sr]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql SQLException]
           [org.testcontainers.containers PostgreSQLContainer]
           [org.testcontainers.utility DockerImageName]))

(def ^:dynamic *ds* nil)

(def ^:private pg-container
  (-> (DockerImageName/parse "postgres:18.4-alpine")
      (PostgreSQLContainer.)))

(defn- postgres-fixture [f]
  (.start pg-container)
  (let [ds (jdbc/get-datasource {:jdbcUrl  (.getJdbcUrl pg-container)
                                 :user     (.getUsername pg-container)
                                 :password (.getPassword pg-container)})]
    (migrations/migrate! ds)
    (binding [*ds* ds]
      (f))))

(defn- truncate-fixture [f]
  (jdbc/execute! *ds* ["TRUNCATE service_requests, audit_events"])
  (f))

(use-fixtures :once postgres-fixture)
(use-fixtures :each truncate-fixture)

(defn- sr-port [] (pg/->PostgresServiceRequestPort *ds*))
(defn- audit-port [] (pg/->PostgresAuditPort *ds*))

;; --- Migrations ---

(deftest migrations-are-recorded-in-metadata-table
  (let [rows (jdbc/execute! *ds*
                            ["SELECT version, description, script, success
                              FROM schema_version
                              ORDER BY installed_rank"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (is (= [{:version "1"
             :description "create service requests"
             :script "V1__create_service_requests.sql"
             :success true}
            {:version "2"
             :description "create audit events"
             :script "V2__create_audit_events.sql"
             :success true}
            {:version "3"
             :description "add service request search vector"
             :script "V3__add_service_request_search_vector.sql"
             :success true}
            {:version "4"
             :description "index service request search vector"
             :script "V4__index_service_request_search_vector.sql"
             :success true}]
           (mapv #(select-keys % [:version :description :script :success]) rows)))))

;; --- ServiceRequestPort ---

(deftest save-returns-record-with-all-fields
  (let [saved (sr/save! (sr-port) {:submitted-by "alice"
                                   :title        "Fix door"
                                   :description  "Room 101 handle broken"})]
    (is (string? (:request_id saved)))
    (is (= "alice" (:submitted_by saved)))
    (is (= "Fix door" (:title saved)))
    (is (= "Room 101 handle broken" (:description saved)))
    (is (= "submitted" (:status saved)))
    (is (string? (:created_at saved)))
    (is (string? (:updated_at saved)))))

(deftest save-does-not-expose-search-vector
  (let [saved (sr/save! (sr-port) {:submitted-by "alice"
                                   :title        "Fix door"
                                   :description  "Room 101 handle broken"})]
    (is (nil? (:search_vector saved)))))

(deftest save-generates-uuidv7
  (let [saved (sr/save! (sr-port) {:submitted-by "alice" :title "T" :description "D"})]
    (is (= "7" (subs (:request_id saved) 14 15)))))

(deftest list-all-returns-empty-when-no-records
  (is (= [] (sr/list-all (sr-port)))))

(deftest list-all-returns-saved-records
  (sr/save! (sr-port) {:submitted-by "alice" :title "Fix door" :description "Room 101"})
  (let [results (sr/list-all (sr-port))]
    (is (= 1 (count results)))
    (is (= "Fix door" (:title (first results))))))

(deftest list-all-returns-all-records
  (sr/save! (sr-port) {:submitted-by "alice" :title "First"  :description "D"})
  (sr/save! (sr-port) {:submitted-by "bob"   :title "Second" :description "D"})
  (is (= 2 (count (sr/list-all (sr-port))))))

(deftest list-all-returns-most-recent-first
  (sr/save! (sr-port) {:submitted-by "alice" :title "Older"  :description "D"})
  (Thread/sleep 50)
  (sr/save! (sr-port) {:submitted-by "alice" :title "Newer" :description "D"})
  (let [results (sr/list-all (sr-port))]
    (is (= "Newer" (:title (first results))))
    (is (= "Older" (:title (second results))))))

(deftest list-all-fields-are-strings
  (sr/save! (sr-port) {:submitted-by "alice" :title "Fix door" :description "Room 101"})
  (let [result (first (sr/list-all (sr-port)))]
    (is (string? (:title result)))
    (is (string? (:request_id result)))
    (is (string? (:created_at result)))))

(deftest list-all-does-not-expose-search-vector
  (sr/save! (sr-port) {:submitted-by "alice" :title "Fix door" :description "Room 101"})
  (is (nil? (:search_vector (first (sr/list-all (sr-port)))))))

(deftest search-returns-empty-when-no-records-match
  (sr/save! (sr-port) {:submitted-by "alice" :title "Fix door" :description "Room 101"})
  (is (= [] (sr/search (sr-port) "printer"))))

(deftest search-matches-title
  (sr/save! (sr-port) {:submitted-by "alice" :title "Printer jam" :description "Room 101"})
  (let [results (sr/search (sr-port) "printer")]
    (is (= 1 (count results)))
    (is (= "Printer jam" (:title (first results))))
    (is (nil? (:rank (first results))))))

(deftest search-matches-description
  (sr/save! (sr-port) {:submitted-by "alice" :title "Office issue" :description "Printer jam in Room 101"})
  (let [results (sr/search (sr-port) "printer")]
    (is (= 1 (count results)))
    (is (= "Office issue" (:title (first results))))))

(deftest search-ranks-title-matches-ahead-of-description-matches
  (sr/save! (sr-port) {:submitted-by "alice" :title "Office issue" :description "Printer jam in Room 101"})
  (sr/save! (sr-port) {:submitted-by "alice" :title "Printer jam" :description "Room 101"})
  (let [[first-result] (sr/search (sr-port) "printer")]
    (is (= "Printer jam" (:title first-result)))
    (is (nil? (:rank first-result)))))

;; --- AuditPort ---

(deftest audit-record-returns-nil
  (let [saved (sr/save! (sr-port) {:submitted-by "alice" :title "T" :description "D"})]
    (is (nil? (audit/record! (audit-port)
                             {:actor      "alice"
                              :action     "submit-service-request"
                              :subject-id (:request_id saved)
                              :metadata   {:title "T"}})))))

(deftest audit-record-persists-to-db
  (let [saved (sr/save! (sr-port) {:submitted-by "alice" :title "T" :description "D"})
        _     (audit/record! (audit-port)
                             {:actor      "alice"
                              :action     "submit-service-request"
                              :subject-id (:request_id saved)
                              :metadata   {:title "T"}})
        rows  (jdbc/execute! *ds* ["SELECT * FROM audit_events"])]
    (is (= 1 (count rows)))))

(deftest audit-event-id-is-uuidv7
  (let [saved (sr/save! (sr-port) {:submitted-by "alice" :title "T" :description "D"})
        _     (audit/record! (audit-port)
                             {:actor      "alice"
                              :action     "submit-service-request"
                              :subject-id (:request_id saved)
                              :metadata   {:title "T"}})
        row   (first (jdbc/execute! *ds* ["SELECT audit_event_id::text FROM audit_events"]
                                    {:builder-fn rs/as-unqualified-lower-maps}))]
    (is (= "7" (subs (:audit_event_id row) 14 15)))))

(deftest audit-record-rejects-missing-service-request
  (let [missing-request-id "01900000-0000-7000-8000-000000000001"
        ex                 (try
                             (audit/record! (audit-port)
                                            {:actor      "alice"
                                             :action     "submit-service-request"
                                             :subject-id missing-request-id
                                             :metadata   {:title "T"}})
                             nil
                             (catch SQLException e
                               e))]
    (is (= "23503" (.getSQLState ex)))
    (is (zero? (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) FROM audit_events"])
                   vals
                   first)))))

(deftest transact-rolls-back-service-request-when-audit-fails
  (is (thrown? Exception
               (pg/transact! *ds*
                             (fn [{:keys [service-request-port audit-port]}]
                               (sr/save! service-request-port
                                         {:submitted-by "alice"
                                          :title        "T"
                                          :description  "D"})
                               (audit/record! audit-port
                                              {:actor      "alice"
                                               :action     "submit-service-request"
                                               :subject-id "not-a-uuid"
                                               :metadata   {:title "T"}})))))
  (is (zero? (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) FROM service_requests"])
                 vals
                 first)))
  (is (zero? (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) FROM audit_events"])
                 vals
                 first))))
