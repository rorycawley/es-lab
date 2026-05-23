(ns es-lab.task-persistence.db.postgres
  (:require [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.service-requests.port :as sr]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- ->response [record]
  (cond-> record
    (:request_id record)  (update :request_id str)
    (:created_at record)  (update :created_at #(-> % .toInstant .toString))
    (:updated_at record)  (update :updated_at #(-> % .toInstant .toString))
    (:subject_id record)  (update :subject_id str)
    (:occurred_at record) (update :occurred_at #(-> % .toInstant .toString))))

(defrecord PostgresServiceRequestPort [ds]
  sr/ServiceRequestPort
  (save! [_ {:keys [submitted-by title description]}]
    (-> (jdbc/execute-one! ds
          ["INSERT INTO service_requests (submitted_by, title, description)
            VALUES (?, ?, ?) RETURNING *"
           submitted-by title description]
          {:builder-fn rs/as-unqualified-lower-maps})
        ->response))
  (list-all [_]
    (mapv ->response
          (jdbc/execute! ds
            ["SELECT * FROM service_requests ORDER BY created_at DESC"]
            {:builder-fn rs/as-unqualified-lower-maps}))))

(defrecord PostgresAuditPort [ds]
  audit/AuditPort
  (record! [_ {:keys [actor action subject-id metadata]}]
    (jdbc/execute-one! ds
      ["INSERT INTO audit_events (actor, action, subject_id, metadata)
        VALUES (?, ?, ?::uuid, ?::jsonb)"
       actor action (str subject-id) (json/write-value-as-string metadata)])))
