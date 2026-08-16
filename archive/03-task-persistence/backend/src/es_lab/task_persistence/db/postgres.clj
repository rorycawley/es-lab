(ns es-lab.task-persistence.db.postgres
  (:require [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.service-requests.port :as sr]
            [es-lab.task-persistence.uuid :as uuid]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- ->response [record]
  (-> record
      (dissoc :rank :search_vector)
      (cond-> (:request_id record) (update :request_id str)
              (:created_at record) (update :created_at #(-> % .toInstant .toString))
              (:updated_at record) (update :updated_at #(-> % .toInstant .toString)))))

(def ^:private service-request-columns
  "request_id, submitted_by, title, description, status, created_at, updated_at")

(defrecord PostgresServiceRequestPort [ds]
  sr/ServiceRequestPort
  (save! [_ {:keys [submitted-by title description]}]
    (-> (jdbc/execute-one! ds
                           ["INSERT INTO service_requests (request_id, submitted_by, title, description)
            VALUES (?, ?, ?, ?) RETURNING *"
                            (uuid/uuid7) submitted-by title description]
                           {:builder-fn rs/as-unqualified-lower-maps})
        ->response))
  (list-all [_]
    (mapv ->response
          (jdbc/execute! ds
                         [(str "SELECT " service-request-columns
                               " FROM service_requests"
                               " ORDER BY created_at DESC, request_id DESC")]
                         {:builder-fn rs/as-unqualified-lower-maps})))
  (search [_ query]
    (mapv ->response
          (jdbc/execute! ds
                         [(str "SELECT " service-request-columns ", "
                               "ts_rank(search_vector, plainto_tsquery('english', ?)) AS rank "
                               "FROM service_requests "
                               "WHERE search_vector @@ plainto_tsquery('english', ?) "
                               "ORDER BY rank DESC, created_at DESC")
                          query query]
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defrecord PostgresAuditPort [ds]
  audit/AuditPort
  (record! [_ {:keys [actor action subject-id metadata]}]
    (jdbc/execute-one! ds
                       ["INSERT INTO audit_events (audit_event_id, actor, action, subject_id, metadata)
        VALUES (?, ?, ?, ?::uuid, ?::jsonb)"
                        (uuid/uuid7) actor action (str subject-id) (json/write-value-as-string metadata)])
    nil))

(defn transact!
  "Execute f inside a single JDBC transaction. f receives a ctx map with
  :service-request-port and :audit-port bound to the same transactional connection,
  so save! and record! either both commit or both roll back."
  [ds f]
  (jdbc/with-transaction [tx ds]
    (f {:service-request-port (->PostgresServiceRequestPort tx)
        :audit-port           (->PostgresAuditPort tx)})))
