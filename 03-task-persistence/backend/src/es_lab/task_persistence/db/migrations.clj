(ns es-lab.task-persistence.db.migrations
  (:import [org.flywaydb.core Flyway]))

(def ^:private migration-locations
  (into-array String ["classpath:database/migrations"]))

(defn migrate! [ds]
  (.. (Flyway/configure)
      (dataSource ds)
      (locations migration-locations)
      (table "schema_version")
      (load)
      (migrate)))
