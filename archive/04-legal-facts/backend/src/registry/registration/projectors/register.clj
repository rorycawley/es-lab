(ns registry.registration.projectors.register
  (:require [com.stuartsierra.component :as component]
            [langohr.channel    :as lch]
            [langohr.consumers  :as lc]
            [langohr.basic      :as lb]
            [next.jdbc.sql      :as sql]
            [registry.messaging.rabbitmq-event-bus :as rmq]
            [registry.messaging.topology           :as topology]))

;; =============================================================================
;; Register projection projector
;;
;; Consumes company-registered events from RabbitMQ.
;; Writes to register_projection — a query model of registered companies.
;;
;; Atomicity note: the company legally exists the moment company-registered is
;; appended to registration_events. That event stream is the legal Register for
;; this project. register_projection is an eventually-consistent cache derived
;; from those facts; if this projector is temporarily down, the legal fact is
;; still current in the event store. The outbox pattern (mark-published!)
;; guarantees at-least-once delivery.
;; =============================================================================

(defn- handle-company-registered [datasource event]
  (sql/insert! datasource :register_projection
               {:registration_number       (:registration/number event)
                :company_name              (:company/name event)
                :application_id            (str (:application/id event))
                :registered_office_address (pr-str (:registered-office-address event))
                :registered_at             (:occurred-at event)}
               {:suffix "ON CONFLICT (registration_number) DO NOTHING"}))

(defn- message-handler [datasource]
  (fn [ch {:keys [delivery-tag]} ^bytes payload]
    (try
      (let [event (rmq/deserialise payload)]
        (when (= :company-registered (:event/type event))
          (handle-company-registered datasource event)))
      (lb/ack ch delivery-tag)
      (catch Exception e
        (println "Register projector error:" (.getMessage e))
        (lb/nack ch delivery-tag false true)))))

(defrecord RegisterProjectorComponent [message-broker database channel]
  component/Lifecycle

  (start [this]
    (println "Starting register projector")
    (let [datasource (:datasource database)
          conn       (:connection message-broker)
          ch         (lch/open conn)
          queue-name (get-in topology/queues [:register-projector :name])]
      (lc/subscribe ch queue-name (message-handler datasource) {:auto-ack false})
      (assoc this :channel ch)))

  (stop [this]
    (println "Stopping register projector")
    (when channel (try (lch/close channel) (catch Exception _)))
    (assoc this :channel nil)))

(defn make-register-projector []
  (map->RegisterProjectorComponent {}))
