(ns lab21.fixture
  "Both systems for the driven-adapter contract suite.

  Postgres is started once for the whole run and its table truncated per
  system, because a container per test would make the suite unbearable and a
  shared table would make it lie."
  (:require [clojure.java.io]
            [clojure.string :as str]
            [lab21.system :as system]
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
      (doseq [statement (->> (slurp (clojure.java.io/resource "schema.sql"))
                             (#(str/replace % #"(?m)--.*$" ""))
                             (re-seq #"(?s)CREATE[^;]+;"))]
        (jdbc/execute! ds [statement]))
      c)))

(defn- postgres-config []
  (let [c @container]
    {:jdbcUrl (.getJdbcUrl c) :user (.getUsername c) :password (.getPassword c)}))

(defn- truncate! []
  (jdbc/execute! (jdbc/get-datasource (postgres-config))
                 ["TRUNCATE event, stream_head, outbox, command_ledger
                   RESTART IDENTITY"]))

(defn systems
  "Every driven-adapter system, as `[label thunk]` pairs.

  The business behaviour suite does not use this fixture: it always enters the
  primary ports with fast in-memory fakes. Set `ESLAB_SKIP_DOCKER=1` to run
  this contract against memory alone."
  [opts]
  (cond-> [["the in-memory adapter" #(system/in-memory opts)]]
    (not (System/getenv "ESLAB_SKIP_DOCKER"))
    (conj ["Postgres" (fn []
                        (let [config (postgres-config)]
                          (truncate!)
                          (system/with-postgres config opts)))])))
