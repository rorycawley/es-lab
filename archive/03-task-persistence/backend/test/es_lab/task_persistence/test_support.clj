(ns es-lab.task-persistence.test-support
  (:require [clojure.string :as str]
            [es-lab.task-persistence.audit.port :as audit]
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
    (vec (rseq @!store)))
  (search [_ query]
    (let [needle  (str/lower-case query)
          in?     (fn [text] (str/includes? (str/lower-case text) needle))
          title?  (fn [{:keys [title]}] (in? title))]
      (->> @!store
           (filter (fn [{:keys [title description]}] (or (in? title) (in? description))))
           (sort-by (fn [r] (if (title? r) 0 1)))
           vec))))

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
