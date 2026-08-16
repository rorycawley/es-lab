(ns es-lab.task-persistence.db.postgres-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.db.migrations :as migrations]
            [es-lab.task-persistence.db.postgres :as pg]
            [es-lab.task-persistence.service-requests.port :as sr]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.testcontainers.containers PostgreSQLContainer]))

(def ^:dynamic *ds* nil)

(defn with-postgres [f]
  (let [container (PostgreSQLContainer. "postgres:18.4-alpine")]
    (.start container)
    (try
      (let [ds (jdbc/get-datasource {:jdbcUrl  (.getJdbcUrl container)
                                     :user     (.getUsername container)
                                     :password (.getPassword container)})]
        (migrations/migrate! ds)
        (binding [*ds* ds]
          (f)))
      (finally
        (.stop container)))))

(defn truncate [f]
  (jdbc/execute! *ds* ["TRUNCATE audit_events, service_requests CASCADE"])
  (f))

(use-fixtures :once with-postgres)
(use-fixtures :each truncate)

(defn- save-one! [ds & {:keys [title description submitted-by]
                         :or   {title        "Default title"
                                description  "Default description"
                                submitted-by "test-user"}}]
  (sr/save! (pg/->PostgresServiceRequestPort ds)
            {:title title :description description :submitted-by submitted-by}))

(deftest migrations-recorded-in-schema-version
  ;; AC-05-02
  (let [rows (jdbc/execute! *ds*
                            ["SELECT version, success FROM schema_version ORDER BY installed_rank"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (is (= 4 (count rows)))
    (is (= ["1" "2" "3" "4"] (map :version rows)))
    (is (every? :success rows))))

(deftest save-returns-all-required-fields
  ;; AC-04-07 (shape)
  (let [saved (save-one! *ds* :title "T" :description "D")]
    (is (= #{:request_id :title :description :status :submitted_by :created_at :updated_at}
           (set (keys saved))))
    (is (= "T" (:title saved)))
    (is (= "D" (:description saved)))
    (is (= "submitted" (:status saved)))
    (is (string? (:request_id saved)))
    (is (string? (:created_at saved)))
    (is (string? (:updated_at saved)))))

(deftest list-all-includes-saved-record
  ;; AC-04-07 (roundtrip)
  (let [saved  (save-one! *ds* :title "Roundtrip")
        listed (sr/list-all (pg/->PostgresServiceRequestPort *ds*))]
    (is (some #(= (:request_id saved) (:request_id %)) listed))))

(deftest list-all-returns-most-recent-first
  ;; AC-04-08
  (let [a       (save-one! *ds* :title "First submitted")
        b       (save-one! *ds* :title "Second submitted")
        results (sr/list-all (pg/->PostgresServiceRequestPort *ds*))
        ids     (map :request_id results)
        pos     (fn [id] (.indexOf ids id))]
    (is (< (pos (:request_id b)) (pos (:request_id a))))))

(deftest search-finds-title-match
  ;; AC-08-05
  (save-one! *ds* :title "Broken printer" :description "Something unrelated")
  (let [results (sr/search (pg/->PostgresServiceRequestPort *ds*) "printer")]
    (is (some #(= "Broken printer" (:title %)) results))))

(deftest search-finds-description-match
  ;; AC-08-06
  (save-one! *ds* :title "Unrelated title" :description "Paper jam on printer")
  (let [results (sr/search (pg/->PostgresServiceRequestPort *ds*) "printer")]
    (is (some #(= "Unrelated title" (:title %)) results))))

(deftest search-ranks-title-above-description
  ;; AC-08-11
  (save-one! *ds* :title "printer issue" :description "Something else")
  (save-one! *ds* :title "Other request" :description "printer is broken")
  (let [results (sr/search (pg/->PostgresServiceRequestPort *ds*) "printer")
        titles  (map :title results)]
    (is (< (.indexOf titles "printer issue")
           (.indexOf titles "Other request")))))

(deftest search-result-fields-are-exactly-seven
  ;; AC-08-12
  (save-one! *ds* :title "Field check" :description "Checking fields")
  (let [results (sr/search (pg/->PostgresServiceRequestPort *ds*) "check")]
    (is (seq results))
    (doseq [r results]
      (is (= #{:request_id :title :description :status :submitted_by :created_at :updated_at}
             (set (keys r)))))))

(deftest audit-event-written-with-correct-fields
  ;; QA-03-01
  (let [saved (save-one! *ds* :submitted-by "alice")]
    (audit/record! (pg/->PostgresAuditPort *ds*)
                   {:actor      "alice"
                    :action     "submit-service-request"
                    :subject-id (:request_id saved)
                    :metadata   {:title (:title saved)}})
    (let [rows (jdbc/execute! *ds*
                              ["SELECT actor, action, subject_id::text FROM audit_events"]
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (is (= 1 (count rows)))
      (is (= "alice" (:actor (first rows))))
      (is (= "submit-service-request" (:action (first rows))))
      (is (= (:request_id saved) (:subject_id (first rows)))))))

(deftest transact-rolls-back-on-audit-failure
  ;; QA-03-03
  (is (thrown? Exception
               (pg/transact! *ds*
                             (fn [{:keys [service-request-port audit-port]}]
                               (sr/save! service-request-port
                                         {:submitted-by "u"
                                          :title        "Should roll back"
                                          :description  "Rollback test"})
                               (audit/record! audit-port
                                              {:actor      "u"
                                               :action     "submit-service-request"
                                               :subject-id (str (java.util.UUID/randomUUID))
                                               :metadata   {}})))))
  (is (empty? (sr/list-all (pg/->PostgresServiceRequestPort *ds*)))))

(deftest fk-constraint-rejects-orphan-audit-event
  ;; AC-05-03
  (let [audit-port (pg/->PostgresAuditPort *ds*)]
    (is (thrown? Exception
                 (audit/record! audit-port
                                {:actor      "user"
                                 :action     "submit-service-request"
                                 :subject-id (str (java.util.UUID/randomUUID))
                                 :metadata   {}})))))
