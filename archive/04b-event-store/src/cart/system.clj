(ns cart.system
  "Component system for the HTTP service."
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.adapter.driven.event-store-sqlite :as sqlite]
            [cart.adapter.driving.http :as http]
            [cart.app.command :as app-command]
            [cart.app.query :as app-query]
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

(defrecord SQLiteDatabase [config datasource]
  component/Lifecycle

  (start [this]
    (if datasource
      this
      (assoc this :datasource (sqlite/make-datasource config))))

  (stop [this]
    (when datasource
      (.close datasource))
    (assoc this :datasource nil)))

(defrecord SQLiteEventStore [database store]
  component/Lifecycle

  (start [this]
    (if store
      this
      (assoc this :store (sqlite/make-store (:datasource database)))))

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

(defrecord CartCommand [event-store retry handler]
  component/Lifecycle

  (start [this]
    (if handler
      this
      (assoc this :handler (app-command/make-event-store-command
                            (:store event-store)
                            retry))))

  (stop [this]
    (assoc this :handler nil)))

(defrecord CartQuery [event-store query]
  component/Lifecycle

  (start [this]
    (if query
      this
      (assoc this :query (app-query/make-event-store-query (:store event-store)))))

  (stop [this]
    (assoc this :query nil)))

(defrecord HttpServer [config cart-command cart-query clock server handler]
  component/Lifecycle

  (start [this]
    (if server
      this
      (let [handler (http/handler (cond-> {:cart-command (:handler cart-command)
                                           :cart-query   (:query cart-query)}
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
   {:store :postgres|:sqlite|:memory
    :db    {:jdbc-url ... :username ... :password ... :pool-size ...}
    :http  {:port 8080}
    :retry optional command retry config
    :clock optional zero-arg fn returning epoch millis}

   Postgres migrations are deliberately not part of service startup. Run Flyway
   as a one-shot deployment step before starting that system. SQLite is
   embedded, so its datasource applies its own adapter migration by default."
  [{:keys [store db http retry clock]
    :or   {store :postgres
           http  {:port 8080}}}]
  (case store
    :postgres
    (component/system-map
     :database (map->Database {:config db})
     :event-store (component/using (map->PostgresEventStore {})
                                   [:database])
     :cart-command (component/using (map->CartCommand {:retry retry})
                                    [:event-store])
     :cart-query (component/using (map->CartQuery {})
                                  [:event-store])
     :http-server (component/using (map->HttpServer {:config http
                                                     :clock clock})
                                   [:cart-command :cart-query]))

    :sqlite
    (component/system-map
     :database (map->SQLiteDatabase {:config db})
     :event-store (component/using (map->SQLiteEventStore {})
                                   [:database])
     :cart-command (component/using (map->CartCommand {:retry retry})
                                    [:event-store])
     :cart-query (component/using (map->CartQuery {})
                                  [:event-store])
     :http-server (component/using (map->HttpServer {:config http
                                                     :clock clock})
                                   [:cart-command :cart-query]))

    :memory
    (component/system-map
     :event-store (map->MemoryEventStore {})
     :cart-command (component/using (map->CartCommand {:retry retry})
                                    [:event-store])
     :cart-query (component/using (map->CartQuery {})
                                  [:event-store])
     :http-server (component/using (map->HttpServer {:config http
                                                     :clock clock})
                                   [:cart-command :cart-query]))

    (throw (ex-info "Unknown store type" {:store store}))))
