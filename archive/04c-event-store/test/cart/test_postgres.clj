(ns cart.test-postgres
  "Owned Testcontainers PostgreSQL/Flyway fixture for Iteration 1 evidence."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [platform.persistence.datasource :as datasource])
  (:import [org.postgresql.ds PGSimpleDataSource]
           [org.testcontainers.containers BindMode GenericContainer Network
            PostgreSQLContainer]
           [org.testcontainers.containers.startupcheck OneShotStartupCheckStrategy]
           [org.testcontainers.utility DockerImageName]))

(def postgres-image "postgres:18.4-alpine")
(def flyway-image "flyway/flyway:13.0.0")

(def ^:dynamic *datasource* nil)
(def ^:dynamic *migrator-datasource* nil)

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

(defn- simple-datasource [url username password]
  (doto (PGSimpleDataSource.)
    (.setURL url)
    (.setUser username)
    (.setPassword password)))

(defn with-postgres [f]
  (let [network   (Network/newNetwork)
        container (postgres-container network)]
    (try
      (.start container)
      (run-flyway! network)
      (let [url        (jdbc-url container)
            app-ds     (datasource/postgres-datasource
                        {:jdbc-url url
                         :username "cart_app"
                         :password "cart_app"
                         :pool-size 10})
            migrator-ds (simple-datasource url "cart_migrator" "cart_migrator")]
        (try
          (binding [*datasource* app-ds
                    *migrator-datasource* migrator-ds]
            (f))
          (finally
            (datasource/close! app-ds))))
      (finally
        (.stop container)
        (.close network)))))

(defn reset-database! []
  (jdbc/execute-one!
   *migrator-datasource*
   ["TRUNCATE cart_history_projection, cart_view_projection,
              command_requests, events, streams"]))
