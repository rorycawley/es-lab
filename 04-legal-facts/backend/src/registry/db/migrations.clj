(ns registry.db.migrations
  (:require [com.stuartsierra.component :as component])
  (:import [org.flywaydb.core Flyway]))

(defn migrate! [datasource]
  (-> (Flyway/configure)
      (.dataSource datasource)
      (.locations (into-array String ["classpath:db/migrations"]))
      (.validateOnMigrate true)
      (.outOfOrder false)
      (.load)
      (.migrate)))

(defrecord MigrationsComponent [database]
  component/Lifecycle
  (start [this]
    (println "Running database migrations")
    (migrate! (:datasource database))
    (println "Migrations complete")
    this)
  (stop [this] this))

(defn make-migrations []
  (map->MigrationsComponent {}))
