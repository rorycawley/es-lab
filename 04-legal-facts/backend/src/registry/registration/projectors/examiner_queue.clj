(ns registry.registration.projectors.examiner-queue
  (:require [com.stuartsierra.component :as component]
            [langohr.channel    :as lch]
            [langohr.consumers  :as lc]
            [langohr.basic      :as lb]
            [next.jdbc.sql      :as sql]
            [registry.messaging.rabbitmq-event-bus :as rmq]
            [registry.messaging.topology           :as topology]))

;; =============================================================================
;; Examiner work queue projector
;;
;; Consumes registration-application-submitted events from RabbitMQ.
;; Writes to the examiner_work_queue read model in Postgres.
;; This is how examiners see submitted applications.
;; =============================================================================

(defn- handle-submitted [datasource event]
  (sql/insert! datasource :examiner_work_queue
               {:application_id   (str (:application/id event))
                :applicant_id     (str (:applicant/id event))
                :company_name     (:company/name event)
                :submitted_at     (:occurred-at event)
                :status           "waiting"}
               {:suffix "ON CONFLICT DO NOTHING"}))

(defn- message-handler [datasource]
  (fn [ch {:keys [delivery-tag]} ^bytes payload]
    (try
      (let [event (rmq/deserialise payload)]
        (when (= :registration-application-submitted (:event/type event))
          (handle-submitted datasource event)))
      (lb/ack ch delivery-tag)
      (catch Exception e
        (println "Examiner queue projector error:" (.getMessage e))
        (lb/nack ch delivery-tag false true)))))

(defrecord ExaminerQueueProjectorComponent [message-broker database channel]
  component/Lifecycle

  (start [this]
    (println "Starting examiner queue projector")
    (let [datasource (:datasource database)
          conn       (:connection message-broker)
          ch         (lch/open conn)
          queue-name (get-in topology/queues [:examiner-work-queue :name])]
      (lc/subscribe ch queue-name (message-handler datasource) {:auto-ack false})
      (assoc this :channel ch)))

  (stop [this]
    (println "Stopping examiner queue projector")
    (when channel (try (lch/close channel) (catch Exception _)))
    (assoc this :channel nil)))

(defn make-examiner-queue-projector []
  (map->ExaminerQueueProjectorComponent {}))
