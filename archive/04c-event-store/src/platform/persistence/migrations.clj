(ns platform.persistence.migrations
  "Separate Flyway entry point for embedded SQLite migrations."
  (:require [clojure.java.io :as io]
            [platform.runtime.config :as config])
  (:import [org.flywaydb.core Flyway]))

(def locations
  {:postgres "classpath:database/migrations/postgres"
   :sqlite "classpath:database/migrations/sqlite"})

(defn migration-location [store]
  (or (get locations store)
      (throw (ex-info "No migration location for store" {:store store}))))

(defn migrate-sqlite! [jdbc-url]
  (when-let [parent (some-> jdbc-url
                            (subs (count "jdbc:sqlite:"))
                            io/file
                            .getParentFile)]
    (.mkdirs parent))
  (let [flyway (.. (Flyway/configure)
                   (dataSource jdbc-url nil nil)
                   (locations (into-array String [(migration-location :sqlite)]))
                   (load))]
    (.migrate flyway)))

(defn -main [& _]
  (let [{:keys [store db]} (config/read-config)]
    (case store
      :sqlite
      (let [result (migrate-sqlite! (:jdbc-url db))]
        (println (format "SQLite migrations complete: %d applied"
                         (.-migrationsExecuted result))))

      :postgres
      (throw (ex-info
              "Run PostgreSQL migrations with `bb migrate` before startup"
              {:store store}))

      :memory
      (println "Memory persistence requires no migrations."))))
