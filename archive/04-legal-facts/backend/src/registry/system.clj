(ns registry.system
  (:require [aero.core                                                     :as aero]
            [clojure.java.io                                               :as io]
            [com.stuartsierra.component                                    :as component]
            [registry.server                                               :as server]
            [registry.db.migrations                                       :as migrations]
            [registry.messaging.topology                                  :as topology]
            [registry.messaging.rabbitmq-event-bus                        :as rmq-bus]
            [registry.messaging.outbox-publisher                          :as outbox]
            [registry.registration.adapters.postgres-event-store          :as pg-store]
            [registry.registration.adapters.postgres-register             :as pg-register]
            [registry.registration.projectors.examiner-queue              :as examiner-proj]
            [registry.registration.projectors.register                    :as register-proj]
            [registry.registration.services.address-validation            :as address]
            [langohr.core    :as rmq]
            [langohr.channel :as lch])
  (:import [com.zaxxer.hikari HikariDataSource HikariConfig])
  (:gen-class))

;; --- DatabaseComponent ---

(defrecord DatabaseComponent [config datasource]
  component/Lifecycle
  (start [this]
    (println "Starting database pool")
    (let [hc (doto (HikariConfig.)
               (.setJdbcUrl           (:url config))
               (.setUsername          (:username config))
               (.setPassword          (:password config))
               (.setMaximumPoolSize   (get config :max-pool-size 10))
               (.setMinimumIdle       (get config :min-idle 2))
               (.setConnectionTimeout (get config :connection-timeout-ms 3000)))]
      (assoc this :datasource (HikariDataSource. hc))))
  (stop [this]
    (println "Stopping database pool")
    (when datasource (.close datasource))
    (assoc this :datasource nil)))

(defn make-database [config] (map->DatabaseComponent {:config config}))

;; --- MessageBrokerComponent ---

(defrecord MessageBrokerComponent [config connection]
  component/Lifecycle
  (start [this]
    (println "Starting RabbitMQ connection")
    (let [conn (rmq/connect {:host     (get config :host "localhost")
                             :port     (get config :port 5672)
                             :username (get config :username "guest")
                             :password (get config :password "guest")
                             :vhost    (get config :vhost "/")})
          ch   (lch/open conn)]
      (try (topology/declare-topology! ch) (finally (lch/close ch)))
      (assoc this :connection conn)))
  (stop [this]
    (println "Stopping RabbitMQ connection")
    (when connection (rmq/close connection))
    (assoc this :connection nil)))

(defn make-message-broker [config] (map->MessageBrokerComponent {:config config}))

;; --- EventStoreComponent ---

(defrecord EventStoreComponent [database event-store]
  component/Lifecycle
  (start [this]
    (println "Starting event store")
    (assoc this :event-store (pg-store/make-postgres-event-store (:datasource database))))
  (stop [this] (assoc this :event-store nil)))

(defn make-event-store [] (map->EventStoreComponent {}))

;; --- EventBusComponent ---

(defrecord EventBusComponent [message-broker event-bus]
  component/Lifecycle
  (start [this]
    (println "Starting event bus")
    (assoc this :event-bus (rmq-bus/make-rabbitmq-event-bus (:connection message-broker))))
  (stop [this] (assoc this :event-bus nil)))

(defn make-event-bus [] (map->EventBusComponent {}))

;; --- OutboxPublisherComponent ---

(defrecord OutboxPublisherComponent [config database message-broker running? poll-thread]
  component/Lifecycle
  (start [this]
    (println "Starting outbox publisher")
    (let [interval   (get config :poll-interval-ms 200)
          batch-size (get config :batch-size 50)
          handle     (pg-store/make-outbox-handle (:datasource database))
          conn       (:connection message-broker)
          running?   (atom true)
          thread     (Thread.
                      (fn []
                        (while @running?
                          (try
                            (outbox/run-poll-cycle! conn handle batch-size)
                            (catch Exception e (println "Outbox error:" (.getMessage e))))
                          (when @running? (Thread/sleep interval)))))]
      (.setName thread "outbox-publisher")
      (.setDaemon thread true)
      (.start thread)
      (assoc this :running? running? :poll-thread thread)))
  (stop [this]
    (println "Stopping outbox publisher")
    (when running? (reset! running? false))
    (when poll-thread (.join poll-thread 5000))
    (assoc this :running? nil :poll-thread nil)))

(defn make-outbox-publisher [config] (map->OutboxPublisherComponent {:config config}))

;; --- WebServerComponent ---

(defrecord WebServerComponent [config event-store database http-server]
  component/Lifecycle
  (start [this]
    (println (str "Starting web server on port " (get config :port 8080)))
    (let [datasource        (:datasource database)
          register-port     (pg-register/make-postgres-register-adapter datasource)
          address-validator (address/make-validator)]
      (assoc this :http-server
             (server/start! {:port              (get config :port 8080)
                             :event-store       (:event-store event-store)
                             :register-port     register-port
                             :address-validator address-validator}))))
  (stop [this]
    (println "Stopping web server")
    (when http-server (server/stop! http-server))
    (assoc this :http-server nil)))

(defn make-web-server [config] (map->WebServerComponent {:config config}))

;; --- System ---
;;
;; Startup order (resolved automatically by Component):
;;
;;   DatabaseComponent
;;     -> MigrationsComponent
;;       -> EventStoreComponent
;;       -> OutboxPublisherComponent
;;       -> ExaminerQueueProjectorComponent
;;       -> RegisterProjectorComponent
;;       -> WebServerComponent
;;   MessageBrokerComponent
;;     -> EventBusComponent (unused — outbox handles publishing)
;;     -> OutboxPublisherComponent
;;     -> ExaminerQueueProjectorComponent
;;     -> RegisterProjectorComponent

(defn make-system [config]
  (component/system-map
   :database
   (make-database (:database config))

   :message-broker
   (make-message-broker (:message-broker config))

   :migrations
   (component/using (migrations/make-migrations) [:database])

   :event-store
   (component/using (make-event-store) [:database :migrations])

   :event-bus
   (component/using (make-event-bus) [:message-broker])

   :outbox-publisher
   (component/using
    (make-outbox-publisher (get config :outbox {}))
    [:database :message-broker :migrations])

   :examiner-queue-projector
   (component/using
    (examiner-proj/make-examiner-queue-projector)
    [:database :message-broker])

   :register-projector
   (component/using
    (register-proj/make-register-projector)
    [:database :message-broker])

   :web-server
   (component/using
    (make-web-server (:web-server config))
    [:event-store :database :migrations])))

;; --- Config ---

(defn load-config []
  (aero/read-config (io/resource "config.edn")))

;; --- Entrypoint ---

(defn -main [& _args]
  (let [system (-> (make-system (load-config)) component/start)]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(component/stop system)))
    (println "Registry system started")))

;; --- REPL helpers ---

(defn start-dev! []  (component/start (make-system (load-config))))
(defn stop-dev!  [s] (component/stop s))
(defn restart-dev! [s]
  (stop-dev! s)
  (component/start (make-system (load-config))))
