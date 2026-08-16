(ns platform.persistence.datasource
  "Concrete JDBC datasource construction at the platform boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.lang AutoCloseable]))

(defn- sqlite-file [jdbc-url]
  (when (str/starts-with? jdbc-url "jdbc:sqlite:")
    (let [path (-> jdbc-url
                   (subs (count "jdbc:sqlite:"))
                   (str/split #"\?" 2)
                   first)]
      (when (and (not (str/blank? path))
                 (not= ":memory:" path)
                 (not (str/starts-with? path "file:")))
        (io/file path)))))

(defn sqlite-datasource
  [{:keys [jdbc-url pool-size busy-timeout-ms]
    :or {pool-size 4 busy-timeout-ms 5000}}]
  (when-let [file (sqlite-file jdbc-url)]
    (io/make-parents file))
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl jdbc-url)
                 (.setMaximumPoolSize pool-size))]
    (doseq [[key value]
            {"busy_timeout" (str busy-timeout-ms)
             "foreign_keys" "true"
             "journal_mode" "WAL"
             "synchronous" "NORMAL"}]
      (.addDataSourceProperty config key value))
    (HikariDataSource. config)))

(defn postgres-datasource
  [{:keys [jdbc-url username password pool-size]
    :or {pool-size 10}}]
  (HikariDataSource.
   (doto (HikariConfig.)
     (.setJdbcUrl jdbc-url)
     (.setUsername username)
     (.setPassword password)
     (.setMaximumPoolSize pool-size))))

(defn close! [datasource]
  (when (instance? AutoCloseable datasource)
    (.close ^AutoCloseable datasource)))
