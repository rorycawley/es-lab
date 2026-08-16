(ns registry.registration.adapters.postgres-event-store
  (:require [next.jdbc             :as jdbc]
            [next.jdbc.date-time]
            [next.jdbc.sql         :as sql]
            [next.jdbc.result-set  :as rs]
            [jsonista.core         :as json]
            [registry.uuid         :as uuid]
            [registry.decider.runner :as runner])
  (:import [org.postgresql.util PGobject]
           [java.time Instant]))

(defn- kw->str [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(def ^:private mapper
  (json/object-mapper {:encode-key-fn kw->str :decode-key-fn keyword}))

(defn- ->pgobject [value]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-value-as-string value mapper))))

(defn- <-pgobject [pgobject]
  (when pgobject (json/read-value (.getValue pgobject) mapper)))

(defn- serialise-event [event]
  (-> event (update :occurred-at str) ->pgobject))

(defn- deserialise-event [row]
  (-> (<-pgobject (:event_data row))
      (update :event/type keyword)
      (update :occurred-at #(Instant/parse %))))

(defn- current-sequence-number [db aggregate-id]
  (-> (jdbc/execute-one!
       db
       ["SELECT COALESCE(MAX(sequence_number), 0) AS seq
         FROM   registration_events
         WHERE  aggregate_id = ?::uuid" aggregate-id]
       {:builder-fn rs/as-unqualified-lower-maps})
      :seq))

(defn- constraint-violation? [e constraint-name]
  (.contains (.getMessage e) constraint-name))

(defn- save-events-tx! [db aggregate-id events]
  (jdbc/with-transaction [tx db]
    (let [current-seq (current-sequence-number tx aggregate-id)]
      (dorun
       (map-indexed
        (fn [i event]
          (sql/insert! tx :registration_events
                       {:id              (:event/id event)
                        :aggregate_id    (uuid/parse aggregate-id)
                        :sequence_number (+ current-seq i 1)
                        :event_type      (name (:event/type event))
                        :event_data      (serialise-event event)
                        :occurred_at     (:occurred-at event)
                        :causation_id    (:event/causation-id event)
                        :correlation_id  (:event/correlation-id event)
                        :published       false}))
        events)))))

(defn fetch-unpublished-events [db batch-size]
  (->> (jdbc/execute!
        db
        ["SELECT id, event_type, event_data, causation_id, correlation_id
          FROM   registration_events
          WHERE  published = false
          ORDER  BY occurred_at ASC
          LIMIT  ?" batch-size]
        {:builder-fn rs/as-unqualified-lower-maps})
       (mapv (fn [row]
               (-> (deserialise-event row)
                   (assoc :event/id            (:id row)
                          :event/causation-id  (:causation_id row)
                          :event/correlation-id (:correlation_id row)))))))

(defn mark-published! [db event-id]
  (jdbc/execute-one!
   db
   ["UPDATE registration_events
     SET    published = true, published_at = now()
     WHERE  id = ?::uuid" event-id]))

(defn make-outbox-handle [datasource]
  {:fetch-unpublished (fn [batch-size] (fetch-unpublished-events datasource batch-size))
   :mark-published!   (fn [event-id]  (mark-published! datasource event-id))})

(defn make-postgres-event-store [datasource]
  (reify runner/EventStore

    (load-events [_ aggregate-id]
      (->> (jdbc/execute!
            datasource
            ["SELECT event_data
              FROM   registration_events
              WHERE  aggregate_id = ?::uuid
              ORDER  BY sequence_number ASC" aggregate-id]
            {:builder-fn rs/as-unqualified-lower-maps})
           (mapv deserialise-event)))

    (save-events! [_ aggregate-id events]
      (try
        (save-events-tx! datasource aggregate-id events)
        (catch org.postgresql.util.PSQLException e
          (condp = (.getSQLState e)
            "23505"
            (cond
              (constraint-violation? e "registration_events_registered_company_name_unique_idx")
              (throw (ex-info "Company name already exists in register"
                              {:reason :business-rule-failed
                               :error  :company-name-already-exists-in-register}))

              (constraint-violation? e "registration_events_pkey")
              (throw (ex-info "Duplicate event" {:error :duplicate-event}))

              :else
              (throw (ex-info "Concurrency conflict" {:error :concurrency-conflict})))
            (throw e)))))))
