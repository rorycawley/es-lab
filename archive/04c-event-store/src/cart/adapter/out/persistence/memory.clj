(ns cart.adapter.out.persistence.memory
  "Atomic in-memory implementation of every cart persistence port."
  (:require [cart.port.out.event-store :as event-store]
            [cart.port.out.idempotency-store :as idempotency-store]
            [cart.port.out.projection-store :as projection-store]
            [cart.port.out.unit-of-work :as unit-of-work]))

(defn empty-state []
  {:streams {}
   :event-ids #{}
   :command-requests {}
   :cart-views {}
   :cart-history {}})

(defn- existing-command-result [state request-id canonical-command]
  (when-let [accepted (get-in state [:command-requests request-id])]
    (if (= canonical-command (:canonical-command accepted))
      {:status :idempotent :result (:result accepted)}
      {:status :request-misuse})))

(defn- expected-revision? [stream expected]
  (case expected
    :absent (nil? stream)
    (= expected (:revision stream))))

(defn- require-acceptance! [state {:keys [events cart-view history-entries]}]
  (when-not (seq events)
    (throw (ex-info "Unit of work requires at least one event" {})))
  (let [revision (:event/revision (peek events))]
    (when-not (and (= revision (:revision cart-view))
                   (= (mapv :event/revision events)
                      (mapv :revision history-entries)))
      (throw (ex-info "Events and projections are not revision-aligned"
                      {:event-revision revision
                       :view-revision (:revision cart-view)}))))
  (let [event-ids (mapv :event/id events)]
    (when (or (not= (count event-ids) (count (set event-ids)))
              (some (:event-ids state) event-ids))
      (throw (ex-info "Event identifier is already accepted"
                      {:event-ids event-ids})))))

(defn- accept [state {:keys [request-id canonical-command stream-key expected
                             events cart-view history-entries successful-result]
                      :as acceptance}]
  (or (when-let [result (existing-command-result state request-id canonical-command)]
        [state result])
      (let [stream (get-in state [:streams stream-key])]
        (if-not (expected-revision? stream expected)
          [state {:status :conflict
                  :current-revision (or (:revision stream) 0)}]
          (do
            (require-acceptance! state acceptance)
            [(-> state
                 (assoc-in [:streams stream-key]
                           {:revision (:event/revision (peek events))
                            :events (into (or (:events stream) []) events)})
                 (update :event-ids into (map :event/id events))
                 (assoc-in [:cart-views stream-key] cart-view)
                 (update-in [:cart-history stream-key]
                            (fnil into [])
                            history-entries)
                 (assoc-in [:command-requests request-id]
                           {:canonical-command canonical-command
                            :result successful-result}))
             {:status :ok :result successful-result}])))))

(defrecord MemoryStore [state]
  event-store/EventStore
  (read-stream [_ stream-key]
    (if-let [stream (get-in @state [:streams stream-key])]
      {:exists? true
       :revision (:revision stream)
       :events (:events stream)}
      {:exists? false :revision 0 :events []}))

  projection-store/ProjectionStore
  (read-cart-view [_ cart-id]
    (get-in @state [:cart-views cart-id]))
  (read-cart-history [_ cart-id]
    (get-in @state [:cart-history cart-id] []))

  idempotency-store/IdempotencyStore
  (find-command-result [_ request-id]
    (get-in @state [:command-requests request-id]))

  unit-of-work/UnitOfWork
  (commit! [_ acceptance]
    (loop []
      (let [before @state
            [after result] (accept before acceptance)]
        (if (identical? before after)
          result
          (if (compare-and-set! state before after)
            result
            (recur)))))))

(defn new-store
  ([] (new-store (empty-state)))
  ([initial-state]
   (->MemoryStore (atom initial-state))))

(defn snapshot
  "Returns immutable state for adapter contract diagnostics."
  [store]
  @(:state store))
