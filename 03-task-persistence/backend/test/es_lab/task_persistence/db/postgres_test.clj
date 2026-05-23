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
            [es-lab.task-persistence.db.postgres :as pg]
            [es-lab.task-persistence.service-requests.port :as sr]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [org.testcontainers.containers PostgreSQLContainer]
           [org.testcontainers.utility DockerImageName]))

(def ^:dynamic *ds* nil)

(def ^:private pg-container
  (-> (DockerImageName/parse "postgres:16-alpine")
      (PostgreSQLContainer.)
      (.withReuse true)))

(defn- postgres-fixture [f]
  (.start pg-container)
  (let [ds (jdbc/get-datasource {:jdbcUrl  (.getJdbcUrl pg-container)
                                 :user     (.getUsername pg-container)
                                 :password (.getPassword pg-container)})]
    (migratus/migrate {:store         :database
                       :migration-dir "migrations"
                       :db            {:datasource ds}})
    (binding [*ds* ds]
      (f))))

(defn- truncate-fixture [f]
  (jdbc/execute! *ds* ["TRUNCATE service_requests, audit_events"])
  (f))

(use-fixtures :once postgres-fixture)
(use-fixtures :each truncate-fixture)

(defn- sr-port [] (pg/->PostgresServiceRequestPort *ds*))
(defn- audit-port [] (pg/->PostgresAuditPort *ds*))

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

(deftest list-all-fields-are-strings
  (sr/save! (sr-port) {:submitted-by "alice" :title "Fix door" :description "Room 101"})
  (let [result (first (sr/list-all (sr-port)))]
    (is (string? (:title result)))
    (is (string? (:request_id result)))
    (is (string? (:created_at result)))))

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
        row   (first (jdbc/execute! *ds* ["SELECT audit_event_id::text FROM audit_events"]))]
    (is (= "7" (subs (str (val (first row))) 14 15)))))
