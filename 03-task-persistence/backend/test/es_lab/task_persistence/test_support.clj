(ns es-lab.task-persistence.test-support
  (:require [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.service-requests.port :as sr]))

(defrecord InMemoryServiceRequestPort [!store]
  sr/ServiceRequestPort
  (save! [_ {:keys [submitted-by title description]}]
    (let [record {:request_id   (str (random-uuid))
                  :submitted_by submitted-by
                  :title        title
                  :description  description
                  :status       "submitted"
                  :created_at   (str (java.time.Instant/now))
                  :updated_at   (str (java.time.Instant/now))}]
      (swap! !store conj record)
      record))
  (list-all [_]
    @!store))

(defrecord InMemoryAuditPort [!store]
  audit/AuditPort
  (record! [_ event]
    (swap! !store conj event)
    nil))

(defn make-ports []
  (let [sr-port    (->InMemoryServiceRequestPort (atom []))
        audit-port (->InMemoryAuditPort (atom []))]
    {:service-request-port sr-port
     :audit-port           audit-port
     :transact!            (fn [f] (f {:service-request-port sr-port
                                       :audit-port           audit-port}))}))
