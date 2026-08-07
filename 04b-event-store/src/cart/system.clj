(ns cart.system
  "Component system for the HTTP service."
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.adapter.driving.http :as http]
            [com.stuartsierra.component :as component]
            [ring.adapter.jetty :as jetty]))

(defrecord Database [config datasource]
  component/Lifecycle

  (start [this]
    (if datasource
      this
      (assoc this :datasource (postgres/make-datasource config))))

  (stop [this]
    (when datasource
      (.close datasource))
    (assoc this :datasource nil)))

(defrecord PostgresEventStore [database store]
  component/Lifecycle

  (start [this]
    (if store
      this
      (assoc this :store (postgres/make-store (:datasource database)))))

  (stop [this]
    (assoc this :store nil)))

(defrecord MemoryEventStore [store]
  component/Lifecycle

  (start [this]
    (if store
      this
      (assoc this :store (memory/make-store))))

  (stop [this]
    (assoc this :store nil)))

(defrecord HttpServer [config event-store retry clock server handler]
  component/Lifecycle

  (start [this]
    (if server
      this
      (let [handler (http/handler (cond-> {:event-store (:store event-store)}
                                    retry (assoc :retry retry)
                                    clock (assoc :clock clock)))
            server  (jetty/run-jetty handler {:port  (:port config 8080)
                                              :join? false})]
        (assoc this :server server :handler handler))))

  (stop [this]
    (when server
      (.stop server))
    (assoc this :server nil :handler nil)))

(defn new-system
  "Builds the service system.

   config:
   {:store :postgres|:memory
    :db    {:jdbc-url ... :username ... :password ... :pool-size ...}
    :http  {:port 8080}
    :retry optional cart.app.handle retry config
    :clock optional zero-arg fn returning epoch millis}

   Migrations are deliberately not part of service startup. Run Flyway as a
   one-shot deployment step before starting this system."
  [{:keys [store db http retry clock]
    :or   {store :postgres
           http  {:port 8080}}}]
  (case store
    :postgres
    (component/system-map
     :database (map->Database {:config db})
     :event-store (component/using (map->PostgresEventStore {})
                                   [:database])
     :http-server (component/using (map->HttpServer {:config http
                                                     :retry retry
                                                     :clock clock})
                                   [:event-store]))

    :memory
    (component/system-map
     :event-store (map->MemoryEventStore {})
     :http-server (component/using (map->HttpServer {:config http
                                                     :retry retry
                                                     :clock clock})
                                   [:event-store]))

    (throw (ex-info "Unknown store type" {:store store}))))
