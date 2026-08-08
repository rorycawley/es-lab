(ns cart.adapter.driven.event-store-sqlite
  "SQLite event store.

   SQLite has a single writer, so appends run inside BEGIN IMMEDIATE. The write
   lock is acquired before the stream version is read, which removes the
   read-then-write gap that would otherwise create optimistic-concurrency races."
  (:require [cart.port.event-store :as port]
            [cart.schema :as schema]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.util UUID]
           [org.flywaydb.core Flyway]))

;; ---------------------------------------------------------------------------
;; Serialisation (SPEC R5.1)
;; ---------------------------------------------------------------------------
;;
;; SQLite stores JSON as UTF-8 text. The DDL checks json_valid/json_type so
;; message_data and message_metadata stay plain JSON objects rather than an
;; opaque Clojure encoding.

(def ^:private migrations-location "classpath:db/sqlite/migration")
(def ^:private default-busy-timeout-ms 5000)

(defn- json-read [v]
  (json/parse-string (str v) true))

(defn- kw->str [k] (subs (str k) 1))

;; ---------------------------------------------------------------------------
;; Connection setup and migrations
;; ---------------------------------------------------------------------------

(defn- sqlite-file-from-jdbc-url [jdbc-url]
  (when (str/starts-with? jdbc-url "jdbc:sqlite:")
    (let [path (-> jdbc-url
                   (subs (count "jdbc:sqlite:"))
                   (str/split #"\?" 2)
                   first)]
      (when (and (not (str/blank? path))
                 (not= ":memory:" path)
                 (not (str/starts-with? path "file:")))
        (io/file path)))))

(defn- ensure-parent-directory! [jdbc-url]
  (when-let [file (sqlite-file-from-jdbc-url jdbc-url)]
    (io/make-parents file)))

(defn- pragma-properties
  "Pragmas that must hold on EVERY pooled connection, handed to the driver as
   connection properties.

   busy_timeout, foreign_keys and synchronous are per-connection state. Running
   them as statements against one borrowed connection at startup configured only
   that connection and left the rest of the pool on driver defaults. As
   connection properties they apply wherever Hikari opens a connection, and they
   stay off the read/append path instead of being re-issued per operation.

   journal_mode belongs to the database file rather than the connection, but
   restating it is harmless and means a fresh file is in WAL mode from its very
   first connection."
  [busy-timeout-ms]
  {"busy_timeout" (str (long busy-timeout-ms))
   "foreign_keys" "true"
   "journal_mode" "WAL"
   "synchronous"  "NORMAL"})

(defn migrate!
  "Applies SQLite migrations to the supplied datasource.

   This is intentionally SQLite-specific. Postgres migrations remain an
   external deployment step/container."
  [datasource]
  (.migrate
   (.. (Flyway/configure)
       (dataSource datasource)
       (locations (into-array String [migrations-location]))
       (table "flyway_schema_history")
       (load)))
  datasource)

(defn make-datasource
  "Creates a SQLite datasource.

   config:
   {:jdbc-url \"jdbc:sqlite:target/cart-event-store.sqlite3\"
    :pool-size 4
    :busy-timeout-ms 5000
    :migrate? true}

   File-backed databases are put in WAL mode for concurrent reads. In-memory
   SQLite keeps its own journal mode; the pragma is harmless there."
  ^HikariDataSource [{:keys [jdbc-url pool-size busy-timeout-ms migrate?]
                      :or   {pool-size 4
                             busy-timeout-ms default-busy-timeout-ms
                             migrate? true}}]
  (let [jdbc-url (or jdbc-url "jdbc:sqlite:target/cart-event-store.sqlite3")]
    (ensure-parent-directory! jdbc-url)
    (let [cfg (doto (HikariConfig.)
                (.setJdbcUrl jdbc-url)
                (.setMaximumPoolSize pool-size))
          _   (doseq [[k v] (pragma-properties busy-timeout-ms)]
                (.addDataSourceProperty cfg k v))
          ds  (HikariDataSource. cfg)]
      (try
        (when migrate? (migrate! ds))
        ds
        (catch Throwable t
          (.close ds)
          (throw t))))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(def ^:private read-sql
  "SELECT stream_position, message_type, message_data, message_metadata
     FROM messages
    WHERE stream_id = ?
    ORDER BY stream_position ASC")

(defn- decode-event
  [{:keys [message_type message_data message_metadata]}]
  (let [metadata (json-read message_metadata)
        event    (cond-> {:type (keyword message_type)
                          :data (json-read message_data)}
                   (seq metadata) (assoc :metadata metadata))]
    (if-let [errs (m/explain schema/Event event)]
      (throw (ex-info "Corrupt event in stream"
                      {:type ::corrupt-event
                       :event event
                       :errors (me/humanize errs)}))
      event)))

(defn- read-stream* [ds stream-id]
  (let [rows (jdbc/execute! ds [read-sql stream-id]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (if (seq rows)
      {:events  (mapv decode-event rows)
       :version (:stream_position (peek rows))
       :exists? true}
      {:events [] :version 0 :exists? false})))

;; ---------------------------------------------------------------------------
;; Appending
;; ---------------------------------------------------------------------------

(defn- stream-type
  "Convention: shopping_cart-<uuid>. The part before the first dash."
  [stream-id]
  (let [idx (.indexOf ^String stream-id "-")]
    (if (pos? idx) (subs stream-id 0 idx) "unknown")))

(defn- valid-expected-version? [expected]
  (or (= :any expected)
      (= :stream-does-not-exist expected)
      (and (integer? expected) (not (neg? expected)))))

(defn- current-position [conn stream-id]
  (or (:stream_position
       (jdbc/execute-one! conn
                          ["SELECT stream_position FROM streams WHERE stream_id = ?"
                           stream-id]
                          {:builder-fn rs/as-unqualified-lower-maps}))
      0))

(defn- conflict [expected current]
  [:conflict {:expected expected
              :current current}])

(defn- insert-stream! [conn stream-id stream-type next-position]
  (jdbc/execute-one! conn
                     ["INSERT INTO streams
                         (stream_id, stream_type, stream_position)
                       VALUES (?, ?, ?)"
                      stream-id stream-type next-position]))

(defn- update-stream! [conn stream-id current-position event-count]
  (jdbc/execute-one! conn
                     ["UPDATE streams
                          SET stream_position = stream_position + ?
                        WHERE stream_id = ?
                          AND stream_position = ?"
                      event-count stream-id current-position]))

(defn- claim-stream! [conn stream-id event-count expected-version]
  (let [current  (long (current-position conn stream-id))
        exists?  (pos? current)
        next-pos (+ current event-count)]
    (cond
      (= :any expected-version)
      (do
        (if exists?
          (update-stream! conn stream-id current event-count)
          (insert-stream! conn stream-id (stream-type stream-id) event-count))
        [:ok {:base-position       current
              :next-position       next-pos
              :created-new-stream? (not exists?)}])

      (= :stream-does-not-exist expected-version)
      (if exists?
        (conflict expected-version current)
        (do
          (insert-stream! conn stream-id (stream-type stream-id) event-count)
          [:ok {:base-position       0
                :next-position       event-count
                :created-new-stream? true}]))

      (not= current expected-version)
      (conflict expected-version current)

      exists?
      (do
        (update-stream! conn stream-id current event-count)
        [:ok {:base-position       current
              :next-position       (+ current event-count)
              :created-new-stream? false}])

      :else
      (do
        (insert-stream! conn stream-id (stream-type stream-id) event-count)
        [:ok {:base-position       0
              :next-position       event-count
              :created-new-stream? true}]))))

(defn- insert-message! [conn stream-id position event]
  (jdbc/execute-one! conn
                     ["INSERT INTO messages
                         (stream_id, stream_position, message_id, message_type,
                          message_data, message_metadata)
                       VALUES (?, ?, ?, ?, ?, ?)"
                      stream-id
                      position
                      (str (UUID/randomUUID))
                      (kw->str (:type event))
                      (json/generate-string (:data event))
                      (json/generate-string (:metadata event {}))]))

(defn- begin-immediate! [conn]
  (jdbc/execute-one! conn ["BEGIN IMMEDIATE"]))

(defn- commit! [conn]
  (jdbc/execute-one! conn ["COMMIT"]))

(defn- rollback! [conn]
  (jdbc/execute-one! conn ["ROLLBACK"]))

(defn- immediate-transaction [conn f]
  (begin-immediate! conn)
  (try
    (let [result (f)]
      (commit! conn)
      result)
    (catch Throwable t
      (try
        (rollback! conn)
        (catch Throwable _))
      (throw t))))

(defn- append!* [ds stream-id events expected-version]
  (when (empty? events)
    (throw (ex-info "append-to-stream called with no events" {:stream-id stream-id})))
  (when-not (valid-expected-version? expected-version)
    (throw (ex-info "Invalid expected version"
                    {:expected-version expected-version})))
  (with-open [conn (jdbc/get-connection ds)]
    (immediate-transaction
     conn
     (fn []
       (let [event-count (count events)
             [outcome data] (claim-stream! conn stream-id event-count expected-version)]
         (if (= :conflict outcome)
           [outcome data]
           (do
             (doseq [[idx event] (map-indexed vector events)]
               (insert-message! conn
                                stream-id
                                (+ (:base-position data) idx 1)
                                event))
             [:ok {:version             (:next-position data)
                   :created-new-stream? (:created-new-stream? data)}])))))))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(defrecord SQLiteEventStore [datasource]
  port/EventStore
  (read-stream [_ stream-id]
    (read-stream* datasource stream-id))
  (append-to-stream [_ stream-id events expected-version]
    (append!* datasource stream-id events expected-version)))

(defn make-store
  "The busy timeout is a property of the datasource's connections, so it is
   configured in make-datasource rather than carried on the store."
  [datasource]
  (->SQLiteEventStore datasource))
