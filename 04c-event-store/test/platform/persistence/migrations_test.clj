(ns platform.persistence.migrations-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [platform.persistence.migrations :as migrations])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest both-adapter-migration-roots-exist
  (doseq [store [:postgres :sqlite]]
    (let [location (migrations/migration-location store)
          resource (subs location (count "classpath:"))]
      (is (some? (io/resource resource))))))

(deftest sqlite-schema-version-one-is-repeatable
  (let [directory (Files/createTempDirectory
                   "cart-migrations-"
                   (make-array FileAttribute 0))
        database  (.resolve directory "cart.sqlite3")
        jdbc-url  (str "jdbc:sqlite:" database)
        first-run (migrations/migrate-sqlite! jdbc-url)
        second-run (migrations/migrate-sqlite! jdbc-url)
        datasource (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (is (= 1 (.-migrationsExecuted first-run)))
    (is (= 0 (.-migrationsExecuted second-run)))
    (is (Files/exists database (make-array java.nio.file.LinkOption 0)))
    (is (= {:migration-count 1}
           (jdbc/execute-one!
            datasource
            ["SELECT count(*) AS migration_count
                FROM flyway_schema_history
               WHERE success = 1"]
            {:builder-fn rs/as-unqualified-kebab-maps})))
    (is (= #{"streams"
             "events"
             "command_requests"
             "cart_view_projection"
             "cart_history_projection"}
           (set (map :name
                     (jdbc/execute!
                      datasource
                      ["SELECT name FROM sqlite_master
                          WHERE type = 'table'
                            AND name IN ('streams', 'events', 'command_requests',
                                         'cart_view_projection',
                                         'cart_history_projection')"]
                      {:builder-fn rs/as-unqualified-kebab-maps})))))))

(deftest both-roots-contain-exactly-schema-version-one
  (doseq [store [:postgres :sqlite]
          :let [root (io/file "resources"
                              "database"
                              "migrations"
                              (name store))]]
    (is (= ["V1__cart_event_store.sql"]
           (->> (file-seq root)
                (filter #(re-matches #"V[0-9]+__.+\.sql" (.getName %)))
                (mapv #(.getName %)))))))
