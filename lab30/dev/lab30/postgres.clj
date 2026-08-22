(ns lab30.postgres
  "A real PostgreSQL 18 database whose locale is part of the search contract."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import (org.testcontainers.containers PostgreSQLContainer)
           (org.testcontainers.utility DockerImageName)))

(def image
  (-> (DockerImageName/parse "postgres:18.4-alpine")
      (.asCompatibleSubstituteFor "postgres")))

(defn- execute-script! [datasource resource-name]
  (jdbc/with-transaction [tx datasource]
    (doseq [statement (->> (slurp (io/resource resource-name))
                           (#(str/split % #";"))
                           (map str/trim)
                           (remove str/blank?))]
      (jdbc/execute! tx [(str statement ";")]))))

(defonce ^:private environment
  (delay
    (let [container (doto (PostgreSQLContainer. image)
                      ;; `casefold('Straße') = 'strasse'` only under a provider
                      ;; with full Unicode case folding. The image's libc
                      ;; default merely lowercases it to `straße`.
                      (.withEnv "POSTGRES_INITDB_ARGS"
                                "--locale-provider=builtin --builtin-locale=PG_UNICODE_FAST")
                      (.withDatabaseName "eslab")
                      (.start))
          admin     (jdbc/get-datasource
                     {:jdbcUrl  (.getJdbcUrl container)
                      :user     (.getUsername container)
                      :password (.getPassword container)})]
      (execute-script! admin "schema.sql")
      {:container container :admin admin})))

(defn config []
  (let [{:keys [container]} @environment]
    {:jdbcUrl  (.getJdbcUrl container)
     :user     "registry_module"
     :password "registry-pass"}))

(defn datasource [] (jdbc/get-datasource (config)))
(defn admin [] (:admin @environment))

(defn truncate! []
  (jdbc/execute! (admin) ["TRUNCATE registry.entity"]))
