(ns cart.adapter.driven.event-store-postgres
  "Postgres event store. All concurrency control lives in the SQL function
   append_to_stream (see resources/db/migration/V1__event_store.sql)."
  (:require [cart.port.event-store :as port]
            [cart.schema :as schema]
            [cognitect.transit :as transit]
            [malli.core :as m]
            [malli.error :as me]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.sql Connection]
           [java.util UUID]
           [org.flywaydb.core Flyway]))

;; ---------------------------------------------------------------------------
;; Serialisation (SPEC R5.1)
;; ---------------------------------------------------------------------------
;;
;; Transit, not plain JSON. Plain JSON turns :cart.event/confirmed into the
;; string "cart.event/confirmed", which matches no evolve method and gets
;; silently dropped by the :default case. Wrong state, no error.

(defn- transit-write ^String [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (.toString out "UTF-8")))

(defn- transit-read [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json)))

(defn- kw->str [k] (subs (str k) 1))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(def ^:private read-sql
  ;; ONE statement (SPEC R4.1). Version is the last event's stream_position, so
  ;; events and version can never come from different snapshots. Served by the
  ;; primary key index.
  "SELECT stream_position, message_type, message_data, message_metadata
     FROM messages
    WHERE stream_id = ?
    ORDER BY stream_position ASC")

(defn- decode-event
  "SPEC R5.2: events crossing back out of storage are validated. A failure
   throws — computing state from history we cannot interpret is worse than
   stopping.

   :metadata is omitted when empty rather than returned as {}, so an event
   written without metadata reads back identical to what went in."
  [{:keys [message_type message_data message_metadata]}]
  (let [metadata (transit-read (str message_metadata))
        event    (cond-> {:type (keyword message_type)
                          :data (transit-read (str message_data))}
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

(def ^:private append-sql
  "SELECT * FROM append_to_stream(?, ?, ?, ?, ?::text[], ?::text[], ?::jsonb[], ?::jsonb[])")

(defn- ->text-array [^Connection conn coll]
  (.createArrayOf conn "text" (into-array String coll)))

(defn- stream-type
  "Convention: shopping_cart-<uuid>. The part before the first dash."
  [stream-id]
  (let [idx (.indexOf ^String stream-id "-")]
    (if (pos? idx) (subs stream-id 0 idx) "unknown")))

(defn- expected->params [expected-version]
  (cond
    (= :any expected-version) [nil false]
    (= :stream-does-not-exist expected-version) [nil true]
    (and (integer? expected-version) (not (neg? expected-version)))
    [(long expected-version) false]
    :else
    (throw (ex-info "Invalid expected version"
                    {:expected-version expected-version}))))

(defn- append!*
  [ds stream-id events expected-version]
  (when (empty? events)
    (throw (ex-info "append-to-stream called with no events" {:stream-id stream-id})))
  (with-open [conn (jdbc/get-connection ds)]
    (let [n   (count events)
          [expected require-new?] (expected->params expected-version)
          row (jdbc/execute-one!
               conn
               [append-sql
                stream-id
                (stream-type stream-id)
                ;; SPEC R4.4 — three modes, two parameters
                expected
                require-new?
                (->text-array conn (repeatedly n #(str (UUID/randomUUID))))
                (->text-array conn (map (comp kw->str :type) events))
                (->text-array conn (map (comp transit-write :data) events))
                ;; Transit like :data, not the literal "{}" — metadata holds
                ;; keywords and instants too, and JSON would flatten them.
                (->text-array conn (map #(transit-write (:metadata % {})) events))]
               {:builder-fn rs/as-unqualified-lower-maps})]
      (if (:success row)
        [:ok {:version             (:next_position row)
              ;; Exact, not >=: a new stream ends at exactly the number of
              ;; events written.
              :created-new-stream? (= (:next_position row) (long n))}]
        [:conflict {:expected expected-version
                    :current  (:current_position row)}]))))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(defrecord PostgresEventStore [datasource]
  port/EventStore
  (read-stream [_ stream-id]
    (read-stream* datasource stream-id))
  (append-to-stream [_ stream-id events expected-version]
    (append!* datasource stream-id events expected-version)))

(defn make-datasource
  "One pool per application, created at the composition root and closed on
   shutdown. Returns a HikariDataSource (closeable)."
  ^HikariDataSource [{:keys [jdbc-url username password pool-size]
                      :or   {pool-size 10}}]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setUsername username)
              (.setPassword password)
              (.setMaximumPoolSize pool-size))]
    (HikariDataSource. cfg)))

(defn migrate!
  "Applies resources/db/migration via Flyway."
  [datasource]
  (-> (Flyway/configure)
      (.dataSource datasource)
      (.locations (into-array String ["classpath:db/migration"]))
      (.load)
      (.migrate)))

(defn make-store [datasource]
  (->PostgresEventStore datasource))
