(ns cart.test-db
  "Testcontainers fixture. Starts one Postgres 18.4 for the whole run,
   applies migrations, and hands out a pooled datasource."
  (:require [cart.adapter.driven.event-store-postgres :as pg]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.testcontainers.containers PostgreSQLContainer]))

(def postgres-image "postgres:18.4-alpine")

(def ^:dynamic *datasource* nil)

(defn with-postgres
  "Use as a :once fixture."
  [f]
  (let [container (doto (PostgreSQLContainer. postgres-image) (.start))]
    (try
      (let [ds (pg/make-datasource {:jdbc-url  (.getJdbcUrl container)
                                    :username  (.getUsername container)
                                    :password  (.getPassword container)
                                    ;; the race tests need real parallelism
                                    :pool-size 8})]
        (try
          (pg/migrate! ds)
          (binding [*datasource* ds] (f))
          (finally (.close ds))))
      (finally (.stop container)))))

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
