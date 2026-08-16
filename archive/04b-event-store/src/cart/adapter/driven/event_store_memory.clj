(ns cart.adapter.driven.event-store-memory
  "In-memory event store, for fast tests.

   Version genuinely is the event count here, but it is still computed inside
   the store rather than by the caller, so the contract matches Postgres and
   the shared contract tests exercise real semantics."
  (:require [cart.port.event-store :as port]))

(defn- version-ok? [expected current exists?]
  (case expected
    :any                   true
    :stream-does-not-exist (not exists?)
    (= expected current)))

(defn- valid-expected-version? [expected]
  (or (= :any expected)
      (= :stream-does-not-exist expected)
      (and (integer? expected) (not (neg? expected)))))

(defrecord InMemoryEventStore [state]
  port/EventStore

  (read-stream [_ stream-id]
    (let [events (get @state stream-id)]
      {:events  (or events [])
       :version (count (or events []))
       :exists? (some? events)}))

  (append-to-stream [_ stream-id events expected-version]
    (when (empty? events)
      (throw (ex-info "append-to-stream called with no events" {:stream-id stream-id})))
    (when-not (valid-expected-version? expected-version)
      (throw (ex-info "Invalid expected version"
                      {:expected-version expected-version})))
    (let [result (atom nil)]
      ;; check and write inside one swap! — the atomic unit, as the SQL
      ;; function is for Postgres.
      (swap! state
             (fn [streams]
               (let [existing (get streams stream-id)
                     current  (count (or existing []))
                     exists?  (some? existing)]
                 (if-not (version-ok? expected-version current exists?)
                   (do (reset! result [:conflict {:expected expected-version
                                                  :current  current}])
                       streams)
                   (do (reset! result [:ok {:version (+ current (count events))
                                            :created-new-stream? (not exists?)}])
                       (update streams stream-id (fnil into []) events))))))
      @result)))

(defn make-store []
  (->InMemoryEventStore (atom {})))
