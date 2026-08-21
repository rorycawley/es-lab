(ns lab25.postgres
  "A real Postgres prepared for the demo and integration suite.

  This follows labs 21, 23 and 24: one Testcontainer for the JVM, migrated
  once, with module tables truncated between scenarios."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import (org.testcontainers.containers PostgreSQLContainer)
           (org.testcontainers.utility DockerImageName)))

(def image
  (-> (DockerImageName/parse "postgres:18.4-alpine")
      (.asCompatibleSubstituteFor "postgres")))

(defn- execute-script! [datasource resource-name]
  ;; `SET ROLE` is session state, so every statement must use the same JDBC
  ;; connection. Executing each against the datasource would silently acquire
  ;; a fresh connection and leave the tables owned by the migration user.
  (jdbc/with-transaction [tx datasource]
    (doseq [statement (->> (slurp (io/resource resource-name))
                           (#(str/split % #";"))
                           (map str/trim)
                           (remove str/blank?))]
      (jdbc/execute! tx [(str statement ";")]))))

(defonce ^:private environment
  (delay
    (let [container (doto (PostgreSQLContainer. image)
                      (.withDatabaseName "eslab")
                      (.start))
          admin     (jdbc/get-datasource
                     {:jdbcUrl  (.getJdbcUrl container)
                      :user     (.getUsername container)
                      :password (.getPassword container)})]
      (execute-script! admin "schema.sql")
      {:container container :admin admin})))

(defn config []
  (let [{:keys [container]} @environment
        url (.getJdbcUrl container)]
    {:catalog  {:jdbcUrl url :user "catalog_module" :password "catalog-pass"}
     :ordering {:jdbcUrl url :user "ordering_module" :password "ordering-pass"}}))

(defn truncate! []
  (let [{:keys [admin]} @environment]
    (jdbc/execute!
     admin
     ["TRUNCATE catalog.product, catalog.outbox, catalog.command_ledger,
                ordering.price_book, ordering.orders, ordering.inbox
        RESTART IDENTITY"])))
