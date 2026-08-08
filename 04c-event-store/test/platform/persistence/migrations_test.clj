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

(deftest empty-sqlite-migration-set-runs-successfully
  (let [directory (Files/createTempDirectory
                   "cart-migrations-"
                   (make-array FileAttribute 0))
        database  (.resolve directory "cart.sqlite3")
        jdbc-url  (str "jdbc:sqlite:" database)
        first-run (migrations/migrate-sqlite! jdbc-url)
        second-run (migrations/migrate-sqlite! jdbc-url)
        datasource (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (is (= 0 (.-migrationsExecuted first-run)))
    (is (= 0 (.-migrationsExecuted second-run)))
    (is (Files/exists database (make-array java.nio.file.LinkOption 0)))
    (is (= {:migration-count 0}
           (jdbc/execute-one!
            datasource
            ["SELECT count(*) AS migration_count
                FROM flyway_schema_history
               WHERE success = 1"]
            {:builder-fn rs/as-unqualified-kebab-maps})))))

(deftest iteration-zero-roots-contain-no-versioned-migration
  (doseq [store [:postgres :sqlite]
          :let [root (io/file "resources"
                              "database"
                              "migrations"
                              (name store))]]
    (is (empty? (filter #(re-matches #"V[0-9]+__.+\.sql" (.getName %))
                        (file-seq root))))))
