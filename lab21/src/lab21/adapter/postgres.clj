(ns lab21.adapter.postgres
  "The Postgres adapter — lab 19's store behind the same protocol.

  All the impedance lives here: JSONB losing keywords (lab 19), JSON losing
  namespaces (lab 20), `java.util.Date` needing a conversion JDBC can infer.
  None of it reaches the application layer, and none of it reaches the core.

  That containment is the whole return on drawing the port."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [lab21.port :as port]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->jsonb [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))

(defn- <-jsonb [^PGobject o]
  (when o (json/read-str (.getValue o) :key-fn keyword)))

;; ---------------------------------------------------------------------------
;; There is no `restore-types`, and there was.
;;
;; It walked every decoded value looking for fields whose keywords JSON had
;; flattened into strings, against a hand-maintained set of field names. Both
;; are gone, because there are no such fields: what the domain writes into
;; `:data` is already expressible in JSON.
;;
;; Note that `:key-fn keyword` above is a **keys-only** facility, and that
;; asymmetry is the whole of the bug it used to paper over. Encoding a keyword
;; to a string is automatic and silent; decoding cannot undo it, because by
;; then a string is all there is. Keys come back because their names are known
;; in advance. Values are not, so they cannot.
;; ---------------------------------------------------------------------------

(defn- ->timestamp [^java.util.Date d] (java.sql.Timestamp. (.getTime d)))

(defn- row->event [row]
  {:event/id          (:event-id row)
   :event/type        (keyword (:event-type row))
   :event/occurred-at (:occurred-at row)
   :event/position    (:global-position row)
   :stream/id         (:stream-id row)
   :stream/version    (:stream-version row)
   :data              (<-jsonb (:data row))
   :metadata          (assoc (<-jsonb (:metadata row))
                             :recorded-at (:recorded-at row))})

(defrecord PostgresStore [datasource]
  port/EventStore
  (read-stream [_ stream-id]
    (mapv row->event
          (jdbc/execute! datasource
                         ["SELECT * FROM event WHERE stream_id = ? ORDER BY stream_version"
                          stream-id] opts)))

  (stream-version [_ stream-id]
    (or (:v (jdbc/execute-one! datasource
                               ["SELECT max(stream_version) AS v FROM event WHERE stream_id = ?"
                                stream-id] opts))
        0))

  (read-since [_ position]
    (mapv row->event
          (jdbc/execute! datasource
                         ["SELECT * FROM event WHERE global_position > ?
                             AND xid < pg_snapshot_xmin(pg_current_snapshot())
                           ORDER BY global_position" position] opts)))

  (append [_ stream-id expected-version command events]
    (jdbc/with-transaction [tx datasource]
      (try
        (mapv (fn [i event]
                (row->event
                 (jdbc/execute-one!
                  tx ["INSERT INTO event (event_id, event_type, stream_id, stream_version,
                                          occurred_at, data, metadata)
                       VALUES (?,?,?,?,?,?,?) RETURNING *"
                      (:event/id event) (name (:event/type event))
                      stream-id (+ expected-version 1 i)
                      (->timestamp (:event/occurred-at event))
                      (->jsonb (:data event))
                      (->jsonb {:causation-id (str (:command/id command))})]
                  opts)))
              (range) events)
        (catch java.sql.SQLException e
          (if (= "23505" (.getSQLState e))
            (throw (ex-info "Concurrent modification of stream"
                            {:stream/id stream-id :expected-version expected-version}))
            (throw e)))))))

(defrecord PostgresOutbox [datasource]
  port/Outbox
  (enqueue [_ messages]
    (mapv (fn [m]
            (jdbc/execute-one!
             datasource
             ["INSERT INTO outbox (message_id, message_type, recipient, payload)
               VALUES (?,?,?,?) RETURNING *"
              (:message-id m) (name (:message-type m)) (name (:recipient m))
              (->jsonb (:payload m))] opts))
          messages))
  (pending [_]
    (mapv #(update % :payload <-jsonb)
          (jdbc/execute! datasource ["SELECT * FROM outbox WHERE sent_at IS NULL ORDER BY id"]
                         opts))))

;; ---------------------------------------------------------------------------
;; The database itself is a component: it has a lifecycle, and the store and
;; outbox depend on it. That dependency is declared in `system.clj` rather than
;; reached for here.
;; ---------------------------------------------------------------------------

(defrecord Database [config datasource]
  component/Lifecycle
  (start [this]
    (let [ds (jdbc/get-datasource config)]
      (doseq [statement (re-seq #"(?s)CREATE[^;]+;" (slurp (io/resource "schema.sql")))]
        (jdbc/execute! ds [statement]))
      (assoc this :datasource ds)))
  (stop [this] (assoc this :datasource nil)))

(defn database [config] (map->Database {:config config}))
(defn store [] (map->PostgresStore {}))
(defn outbox [] (map->PostgresOutbox {}))
