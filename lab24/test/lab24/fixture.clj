(ns lab24.fixture
  "Both systems, so one suite can run against both.

  Postgres is started once for the whole run and its table truncated per
  system, because a container per test would make the suite unbearable and a
  shared table would make it lie."
  (:require [clojure.java.io]
            [lab24.system :as system]
            [next.jdbc :as jdbc])
  (:import (org.testcontainers.containers PostgreSQLContainer)
           (org.testcontainers.utility DockerImageName)))

(defonce ^:private container
  (delay
    (let [c (doto (PostgreSQLContainer.
                   (-> (DockerImageName/parse "postgres:18.4-alpine")
                       (.asCompatibleSubstituteFor "postgres")))
              (.withDatabaseName "eslab")
              (.start))
          ds (jdbc/get-datasource {:jdbcUrl (.getJdbcUrl c)
                                   :user (.getUsername c)
                                   :password (.getPassword c)})]
      ;; Migrate once, here — the tables must exist before a test can truncate
      ;; them, and the Database component migrates on start, which is later.
      (doseq [statement (re-seq #"(?s)CREATE[^;]+;"
                                (slurp (clojure.java.io/resource "schema.sql")))]
        (jdbc/execute! ds [statement]))
      c)))

(defn- postgres-config []
  (let [c @container]
    {:jdbcUrl (.getJdbcUrl c) :user (.getUsername c) :password (.getPassword c)}))

(defn- truncate! []
  (jdbc/execute! (jdbc/get-datasource (postgres-config))
                 ["TRUNCATE event, outbox RESTART IDENTITY"]))

(defn systems
  "Every adapter, as `[label thunk]` pairs.

  Set `ESLAB_SKIP_DOCKER=1` to run the in-memory half alone — the suite still
  passes, which is itself worth knowing."
  [opts]
  (cond-> [["the in-memory adapter" #(system/in-memory opts)]]
    (not (System/getenv "ESLAB_SKIP_DOCKER"))
    (conj ["Postgres" (fn []
                        (let [config (postgres-config)]
                          (truncate!)
                          (system/with-postgres config opts)))])))
