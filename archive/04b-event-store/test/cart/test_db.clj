(ns cart.test-db
  "Testcontainers fixture. Starts one Postgres 18.4 for the whole run,
   runs Flyway in a one-shot container, and hands out a pooled datasource."
  (:require [cart.adapter.driven.event-store-postgres :as pg]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.testcontainers.containers BindMode GenericContainer Network PostgreSQLContainer]
           [org.testcontainers.containers.startupcheck OneShotStartupCheckStrategy]
           [org.testcontainers.utility DockerImageName]))

(def postgres-image "postgres:18.4-alpine")
(def flyway-image "flyway/flyway:13.0.0")

(def ^:dynamic *datasource* nil)
(def ^:dynamic *jdbc-url* nil)

(def app-username "cart_app")
(def app-password "cart_app")

(defn- host-path [path]
  (.getAbsolutePath (io/file path)))

(defn- event-store-jdbc-url [^PostgreSQLContainer container]
  (format "jdbc:postgresql://%s:%d/event_store"
          (.getHost container)
          (.getMappedPort container (Integer/valueOf 5432))))

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
    (.withFileSystemBind (host-path "resources/db/postgres/migration")
                         "/flyway/migrations"
                         BindMode/READ_ONLY)
    (.withCommand (into-array String
                              ["-url=jdbc:postgresql://postgres:5432/event_store"
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
      (finally
        (.stop container)))))

(defn with-postgres
  "Use as a :once fixture."
  [f]
  (let [network   (Network/newNetwork)
        container (postgres-container network)]
    (try
      (.start container)
      (run-flyway! network)
      (let [jdbc-url (event-store-jdbc-url container)
            ds       (pg/make-datasource
                      {:jdbc-url  jdbc-url
                       :username  "postgres"
                       :password  "postgres"
                       ;; the race tests need real parallelism
                       :pool-size 8})]
        (try
          (binding [*datasource* ds
                    *jdbc-url*    jdbc-url]
            (f))
          (finally (.close ds))))
      (finally
        (.stop container)
        (.close network)))))

(defn app-datasource []
  (pg/make-datasource {:jdbc-url  *jdbc-url*
                       :username  app-username
                       :password  app-password
                       :pool-size 2}))

(defn truncate!
  "Between tests. Cheaper than restarting the container."
  [ds]
  (jdbc/execute! ds ["TRUNCATE messages, streams"]))

(defn stream-id []
  (str "shopping_cart-" (random-uuid)))

(defn count-messages [ds stream-id]
  (:count (jdbc/execute-one! ds ["SELECT count(*) AS count FROM messages WHERE stream_id = ?"
                                 stream-id]
                             {:builder-fn rs/as-unqualified-lower-maps})))

(defn positions
  "The stream_position values actually written, in order."
  [ds stream-id]
  (mapv :stream_position
        (jdbc/execute! ds ["SELECT stream_position FROM messages
                             WHERE stream_id = ? ORDER BY stream_position" stream-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn metadata-json
  "The raw message_metadata text, for proving the column is really written."
  [ds stream-id position]
  (str (:message_metadata
        (jdbc/execute-one! ds ["SELECT message_metadata FROM messages
                                 WHERE stream_id = ? AND stream_position = ?"
                               stream-id position]
                           {:builder-fn rs/as-unqualified-lower-maps}))))

(defn stream-version [ds stream-id]
  (:stream_position
   (jdbc/execute-one! ds ["SELECT stream_position FROM streams WHERE stream_id = ?" stream-id]
                      {:builder-fn rs/as-unqualified-lower-maps})))
