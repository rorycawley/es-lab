(ns platform.persistence.postgres-migrations-test
  "Large Iteration 1 test for the production PostgreSQL migration topology."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.testcontainers.containers BindMode GenericContainer Network
            PostgreSQLContainer]
           [org.testcontainers.containers.startupcheck
            OneShotStartupCheckStrategy]
           [org.testcontainers.utility DockerImageName]
           [org.postgresql.ds PGSimpleDataSource]))

(def postgres-image "postgres:18.4-alpine")
(def flyway-image "flyway/flyway:13.0.0")

(def ^:dynamic *migration-datasource* nil)
(def ^:dynamic *app-datasource* nil)

(defn- host-path [path]
  (.getAbsolutePath (io/file path)))

(defn- postgres-container [network]
  (doto (PostgreSQLContainer. (DockerImageName/parse postgres-image))
    (.withDatabaseName "postgres")
    (.withUsername "postgres")
    (.withPassword "postgres")
    (.withNetwork network)
    (.withNetworkAliases (into-array String ["postgres"]))
    (.withFileSystemBind (host-path "resources/docker/postgres/initdb")
                         "/docker-entrypoint-initdb.d"
                         BindMode/READ_ONLY)))

(defn- flyway-container [network]
  (doto (GenericContainer. (DockerImageName/parse flyway-image))
    (.withNetwork network)
    (.withFileSystemBind
     (host-path "resources/database/migrations/postgres")
     "/flyway/migrations"
     BindMode/READ_ONLY)
    (.withCommand
     (into-array String
                 ["-url=jdbc:postgresql://postgres:5432/cart"
                  "-user=cart_migrator"
                  "-password=cart_migrator"
                  "-locations=filesystem:/flyway/migrations"
                  "-connectRetries=60"
                  "migrate"]))
    (.withStartupCheckStrategy (OneShotStartupCheckStrategy.))))

(defn- run-flyway! [network]
  (let [container (flyway-container network)]
    (try
      (.start container)
      (let [state (.getState (.getCurrentContainerInfo container))
            exit  (.getExitCodeLong state)]
        (when-not (zero? exit)
          (throw (ex-info "Flyway migration container failed"
                          {:exit exit :logs (.getLogs container)}))))
      (finally
        (.stop container)))))

(defn- jdbc-url [container]
  (format "jdbc:postgresql://%s:%d/cart"
          (.getHost container)
          (.getMappedPort container (Integer/valueOf 5432))))

(defn- datasource [url username password]
  (doto (PGSimpleDataSource.)
    (.setURL url)
    (.setUser username)
    (.setPassword password)))

(defn- with-postgres [f]
  (let [network   (Network/newNetwork)
        container (postgres-container network)]
    (try
      (.start container)
      (run-flyway! network)
      (run-flyway! network)
      (let [url (jdbc-url container)]
        (binding [*migration-datasource*
                  (datasource url "cart_migrator" "cart_migrator")
                  *app-datasource*
                  (datasource url "cart_app" "cart_app")]
          (f)))
      (finally
        (.stop container)
        (.close network)))))

(use-fixtures :once with-postgres)

(deftest postgres-migration-is-repeatable-and-owned-by-the-migrator
  (is (= {:table-name "flyway_schema_history"
          :table-owner "cart_migrator"}
         (jdbc/execute-one!
          *migration-datasource*
          ["SELECT tablename AS table_name, tableowner AS table_owner
              FROM pg_tables
             WHERE schemaname = 'public'
               AND tablename = 'flyway_schema_history'"]
          {:builder-fn rs/as-unqualified-kebab-maps})))
  (is (= {:migration-count 1}
         (jdbc/execute-one!
          *migration-datasource*
          ["SELECT count(*) AS migration_count
              FROM flyway_schema_history
             WHERE success"]
          {:builder-fn rs/as-unqualified-kebab-maps})))
  (is (= #{"streams"
           "events"
           "command_requests"
           "cart_view_projection"
           "cart_history_projection"}
         (set (map :table-name
                   (jdbc/execute!
                    *migration-datasource*
                    ["SELECT tablename AS table_name
                        FROM pg_tables
                       WHERE schemaname = 'public'
                         AND tablename IN ('streams', 'events', 'command_requests',
                                           'cart_view_projection',
                                           'cart_history_projection')"]
                    {:builder-fn rs/as-unqualified-kebab-maps}))))))

(deftest runtime-role-can-connect-to-the-migrated-database
  (is (= {:database "cart" :database-user "cart_app"}
         (jdbc/execute-one!
          *app-datasource*
          ["SELECT current_database() AS database,
                  current_user AS database_user"]
          {:builder-fn rs/as-unqualified-kebab-maps}))))
