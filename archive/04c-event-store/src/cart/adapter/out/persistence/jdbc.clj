(ns cart.adapter.out.persistence.jdbc
  "Shared JDBC mechanics behind the SQLite and PostgreSQL adapters."
  (:require [cart.port.out.event-store :as event-store]
            [cart.port.out.idempotency-store :as idempotency-store]
            [cart.port.out.projection-store :as projection-store]
            [cart.port.out.unit-of-work :as unit-of-work]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.sql Timestamp]
           [java.time Instant OffsetDateTime ZoneOffset]
           [java.util HexFormat UUID]
           [org.postgresql.util PGobject]))

(def ^:private rows {:builder-fn rs/as-unqualified-kebab-maps})

(defn- json-string [value]
  (json/generate-string value))

(defn- pg-json [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json-string value))))

(defn- json-param [dialect value]
  (if (= :postgres dialect) (pg-json value) (json-string value)))

(defn- json-value [value]
  (json/parse-string
   (if (instance? PGobject value)
     (.getValue ^PGobject value)
     (str value))
   true))

(defn- db-id [dialect value]
  (if (= :postgres dialect) value (str value)))

(defn- db-time [dialect ^Instant value]
  (if (= :postgres dialect)
    (OffsetDateTime/ofInstant value ZoneOffset/UTC)
    (str value)))

(defn- instant [value]
  (cond
    (instance? Instant value) value
    (instance? OffsetDateTime value) (.toInstant ^OffsetDateTime value)
    (instance? Timestamp value) (.toInstant ^Timestamp value)
    :else (Instant/parse (str value))))

(defn- uuid [value]
  (if (instance? UUID value) value (UUID/fromString (str value))))

(defn- event-data->json [{:keys [cart-id product-id quantity]}]
  {:cart-id (str cart-id)
   :product-id (str product-id)
   :quantity quantity})

(defn- event-data<-json [value]
  (let [{:keys [cart-id product-id quantity]} (json-value value)]
    {:cart-id (uuid cart-id)
     :product-id (uuid product-id)
     :quantity quantity}))

(defn- items->json [items]
  (->> items
       (sort-by (comp str key))
       (mapv (fn [[product-id quantity]]
               {:product-id (str product-id) :quantity quantity}))))

(defn- items<-json [value]
  (into {} (map (fn [{:keys [product-id quantity]}]
                  [(uuid product-id) quantity]))
        (json-value value)))

(defn- business-data->json [data]
  (cond-> data
    (:product-id data) (update :product-id str)))

(defn- business-data<-json [value]
  (cond-> (json-value value)
    (:product-id (json-value value)) (update :product-id uuid)))

(defn- sha-256 [value]
  (->> (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes ^String value StandardCharsets/UTF_8))
       (.formatHex (HexFormat/of))))

(defn- read-stream* [dialect datasource stream-key]
  (let [result (jdbc/execute!
                datasource
                ["SELECT s.current_revision,
                         e.stream_revision, e.event_id, e.event_type,
                         e.event_version, e.event_data, e.event_metadata,
                         e.accepted_at
                    FROM streams s
                    JOIN events e ON e.stream_id = s.stream_id
                   WHERE s.stream_id = ?
                   ORDER BY e.stream_revision"
                 (db-id dialect stream-key)]
                rows)]
    (if (seq result)
      (let [current (:current-revision (peek result))
            events  (mapv (fn [row]
                            {:event/id (uuid (:event-id row))
                             :event/type (keyword (:event-type row))
                             :event/version (:event-version row)
                             :event/revision (:stream-revision row)
                             :event/data (event-data<-json (:event-data row))
                             :event/metadata (json-value (:event-metadata row))
                             :event/accepted-at (instant (:accepted-at row))})
                          result)]
        (when-not (= current (:event/revision (peek events)))
          (throw (ex-info "Stream row and events are inconsistent"
                          {:stream-key stream-key :current current})))
        {:exists? true :revision current :events events})
      {:exists? false :revision 0 :events []})))

(defn- read-cart-view* [dialect datasource cart-id]
  (when-let [row (jdbc/execute-one!
                  datasource
                  ["SELECT cart_id, revision, status, items
                      FROM cart_view_projection
                     WHERE cart_id = ?"
                   (db-id dialect cart-id)]
                  rows)]
    {:cart-id (uuid (:cart-id row))
     :revision (:revision row)
     :status (keyword (:status row))
     :items (items<-json (:items row))}))

(defn- read-history* [dialect datasource cart-id]
  (mapv (fn [row]
          {:cart-id (uuid (:cart-id row))
           :revision (:revision row)
           :change-type (keyword (:change-type row))
           :accepted-at (instant (:accepted-at row))
           :business-data (business-data<-json (:business-data row))})
        (jdbc/execute!
         datasource
         ["SELECT cart_id, revision, change_type, accepted_at, business_data
             FROM cart_history_projection
            WHERE cart_id = ?
            ORDER BY revision"
          (db-id dialect cart-id)]
         rows)))

(defn- find-command* [dialect connectable request-id]
  (when-let [row (jdbc/execute-one!
                  connectable
                  ["SELECT canonical_input, original_result
                      FROM command_requests
                     WHERE request_id = ?"
                   (db-id dialect request-id)]
                  rows)]
    {:canonical-command (json-value (:canonical-input row))
     :result (json-value (:original-result row))}))

(defn- update-count [result]
  (:next.jdbc/update-count result 0))

(defn- claim-stream! [dialect tx stream-key expected event-count]
  (let [stream-id (db-id dialect stream-key)]
    (case expected
      :absent
      (let [result (if (= :postgres dialect)
                     (jdbc/execute-one!
                      tx
                      ["INSERT INTO streams
                           (stream_id, stream_type, subject_id, current_revision)
                         VALUES (?, 'cart', ?, ?)
                         ON CONFLICT DO NOTHING
                         RETURNING current_revision"
                       stream-id stream-id event-count]
                      rows)
                     (jdbc/execute-one!
                      tx
                      ["INSERT OR IGNORE INTO streams
                           (stream_id, stream_type, subject_id, current_revision)
                         VALUES (?, 'cart', ?, ?)"
                       stream-id stream-id event-count]))]
        (if (if (= :postgres dialect) (some? result) (pos? (update-count result)))
          {:status :claimed :base-revision 0 :next-revision event-count}
          {:status :conflict}))

      (let [result (if (= :postgres dialect)
                     (jdbc/execute-one!
                      tx
                      ["UPDATE streams
                           SET current_revision = current_revision + ?
                         WHERE stream_id = ? AND current_revision = ?
                         RETURNING current_revision"
                       event-count stream-id expected]
                      rows)
                     (jdbc/execute-one!
                      tx
                      ["UPDATE streams
                           SET current_revision = current_revision + ?
                         WHERE stream_id = ? AND current_revision = ?"
                       event-count stream-id expected]))]
        (if (if (= :postgres dialect) (some? result) (pos? (update-count result)))
          {:status :claimed
           :base-revision expected
           :next-revision (+ expected event-count)}
          {:status :conflict})))))

(defn- insert-event! [dialect tx stream-key event]
  (jdbc/execute-one!
   tx
   ["INSERT INTO events
        (stream_id, stream_revision, event_id, event_type, event_version,
         event_data, event_metadata, accepted_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    (db-id dialect stream-key)
    (:event/revision event)
    (db-id dialect (:event/id event))
    (name (:event/type event))
    (:event/version event)
    (json-param dialect (event-data->json (:event/data event)))
    (json-param dialect (:event/metadata event))
    (db-time dialect (:event/accepted-at event))]))

(defn- upsert-view! [dialect tx view]
  (jdbc/execute-one!
   tx
   ["INSERT INTO cart_view_projection (cart_id, revision, status, items)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (cart_id) DO UPDATE
      SET revision = excluded.revision,
          status = excluded.status,
          items = excluded.items"
    (db-id dialect (:cart-id view))
    (:revision view)
    (name (:status view))
    (json-param dialect (items->json (:items view)))]))

(defn- insert-history! [dialect tx entry]
  (jdbc/execute-one!
   tx
   ["INSERT INTO cart_history_projection
        (cart_id, revision, change_type, accepted_at, business_data)
      VALUES (?, ?, ?, ?, ?)"
    (db-id dialect (:cart-id entry))
    (:revision entry)
    (name (:change-type entry))
    (db-time dialect (:accepted-at entry))
    (json-param dialect (business-data->json (:business-data entry)))]))

(defn- insert-command! [dialect tx acceptance]
  (let [canonical-json (json-string (:canonical-command acceptance))
        accepted-at    (:event/accepted-at (peek (:events acceptance)))]
    (jdbc/execute-one!
     tx
     ["INSERT INTO command_requests
          (request_id, command_type, canonical_input, canonical_input_hash,
           cart_id, original_result, accepted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
      (db-id dialect (:request-id acceptance))
      (get-in acceptance [:canonical-command :command-type])
      (json-param dialect (:canonical-command acceptance))
      (sha-256 canonical-json)
      (db-id dialect (:stream-key acceptance))
      (json-param dialect (:successful-result acceptance))
      (db-time dialect accepted-at)])))

(defn- current-revision [dialect tx stream-key]
  (or (:current-revision
       (jdbc/execute-one!
        tx
        ["SELECT current_revision FROM streams WHERE stream_id = ?"
         (db-id dialect stream-key)]
        rows))
      0))

(defn- request-lock-key [^UUID request-id]
  (bit-xor (.getMostSignificantBits request-id)
           (.getLeastSignificantBits request-id)))

(defn- require-acceptance!
  [{:keys [stream-key expected events cart-view history-entries]}]
  (let [base-revision (if (= :absent expected) 0 expected)
        revisions     (mapv :event/revision events)
        expected-revisions
        (when (integer? base-revision)
          (vec (range (inc base-revision)
                      (+ base-revision (count events) 1))))]
    (when-not (and (seq events)
                   expected-revisions
                   (= expected-revisions revisions)
                   (= (peek revisions) (:revision cart-view))
                   (= revisions (mapv :revision history-entries))
                   (= stream-key (:cart-id cart-view))
                   (every? #(= stream-key (get-in % [:event/data :cart-id]))
                           events)
                   (every? #(= stream-key (:cart-id %)) history-entries))
      (throw (ex-info "Events and projections are not acceptance-aligned"
                      {:stream-key stream-key
                       :expected expected
                       :event-revisions revisions
                       :view-revision (:revision cart-view)})))))

(defn- commit-on-connection [dialect tx acceptance]
  (when (= :postgres dialect)
    (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(?)"
                           (request-lock-key (:request-id acceptance))]))
  (if-let [accepted (find-command* dialect tx (:request-id acceptance))]
    (if (= (:canonical-command acceptance) (:canonical-command accepted))
      {:status :idempotent :result (:result accepted)}
      {:status :request-misuse})
    (let [claimed (claim-stream! dialect
                                 tx
                                 (:stream-key acceptance)
                                 (:expected acceptance)
                                 (count (:events acceptance)))]
      (if (= :conflict (:status claimed))
        {:status :conflict
         :current-revision (current-revision dialect tx (:stream-key acceptance))}
        (do
          (doseq [event (:events acceptance)]
            (insert-event! dialect tx (:stream-key acceptance) event))
          (upsert-view! dialect tx (:cart-view acceptance))
          (doseq [entry (:history-entries acceptance)]
            (insert-history! dialect tx entry))
          (insert-command! dialect tx acceptance)
          {:status :ok :result (:successful-result acceptance)})))))

(defn- immediate-transaction [datasource f]
  (with-open [connection (jdbc/get-connection datasource)]
    (jdbc/execute-one! connection ["BEGIN IMMEDIATE"])
    (try
      (let [result (f connection)]
        (jdbc/execute-one! connection ["COMMIT"])
        result)
      (catch Throwable throwable
        (try
          (jdbc/execute-one! connection ["ROLLBACK"])
          (catch Throwable _))
        (throw throwable)))))

(defrecord JdbcStore [dialect datasource]
  event-store/EventStore
  (read-stream [_ stream-key]
    (read-stream* dialect datasource stream-key))

  projection-store/ProjectionStore
  (read-cart-view [_ cart-id]
    (read-cart-view* dialect datasource cart-id))
  (read-cart-history [_ cart-id]
    (read-history* dialect datasource cart-id))

  idempotency-store/IdempotencyStore
  (find-command-result [_ request-id]
    (find-command* dialect datasource request-id))

  unit-of-work/UnitOfWork
  (commit! [_ acceptance]
    (require-acceptance! acceptance)
    (if (= :sqlite dialect)
      (immediate-transaction datasource #(commit-on-connection dialect % acceptance))
      (jdbc/with-transaction [tx datasource]
        (commit-on-connection dialect tx acceptance)))))

(defn new-store [dialect datasource]
  (->JdbcStore dialect datasource))
