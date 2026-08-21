(ns lab20.fixture
  "A real Postgres, started for the suite and thrown away after it.

  Testcontainers rather than a shared database, because these tests turn on
  transaction timing and a stray connection from another run would make them
  lie. The container is the fixture."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc])
  (:import (org.testcontainers.containers PostgreSQLContainer)
           (org.testcontainers.utility DockerImageName)))

(def image
  "Postgres 18 specifically: `uuidv7()` and `xid8` both matter to this lab."
  (-> (DockerImageName/parse "postgres:18.4-alpine")
      (.asCompatibleSubstituteFor "postgres")))

(defonce ^:private container
  (delay
    (doto (PostgreSQLContainer. image)
      (.withDatabaseName "eslab")
      (.start))))

(defn datasource
  []
  (let [c @container]
    (jdbc/get-datasource {:jdbcUrl  (.getJdbcUrl c)
                          :user     (.getUsername c)
                          :password (.getPassword c)})))

(defn migrate!
  [ds]
  (doseq [statement (->> (slurp (io/resource "schema.sql"))
                         (re-seq #"(?s)CREATE[^;]+;")
                         (map str))]
    (jdbc/execute! ds [statement])))

(defn truncate!
  [ds]
  (jdbc/execute! ds ["TRUNCATE event, outbox, inbox, command_ledger RESTART IDENTITY"]))

(defn with-store
  "Fixture: a migrated, empty `event` table for each test."
  [f]
  (let [ds (datasource)]
    (migrate! ds)
    (truncate! ds)
    (f)))
