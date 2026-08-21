(ns lab21.adapter.memory
  "A working fake for the complete persistence boundary.

  Events, the command ledger and outgoing messages live in one atom so the
  fake preserves the same atomic command-outcome contract as PostgreSQL."
  (:require [com.stuartsierra.component :as component]
            [lab21.port :as port]))

(defn- request-identity [stream-id command]
  {:stream-id stream-id
   :command-type (:command/type command)
   :data (:data command)})

(defn- contains-keyword-value? [value]
  (cond
    (keyword? value) true
    (map? value) (boolean (some contains-keyword-value? (vals value)))
    (coll? value) (boolean (some contains-keyword-value? value))
    :else false))

(defn- validate-command! [command]
  (when-not (uuid? (:command/id command))
    (throw (ex-info "Invalid command id" {:command/id (:command/id command)})))
  (when-not (uuid? (:correlation-id command))
    (throw (ex-info "Invalid correlation id"
                    {:correlation-id (:correlation-id command)})))
  (when-not (map? (:data command))
    (throw (ex-info "Command data must be a map" {:data (:data command)})))
  (when (contains-keyword-value? (:data command))
    (throw (ex-info "Keyword values are not valid command data"
                    {:reason :lossy-json-value :data (:data command)}))))

(defn- validate-outcome! [events messages]
  (when-not (= (count events) (count (distinct (map :event/id events))))
    (throw (ex-info "Duplicate event ids inside outcome"
                    {:reason :duplicate-event-id})))
  (when-not (= (count messages) (count (distinct (map :message-id messages))))
    (throw (ex-info "Duplicate message ids inside outcome"
                    {:reason :duplicate-message-id})))
  (doseq [event events]
    (when-not (and (uuid? (:event/id event))
                   (keyword? (:event/type event))
                   (inst? (:event/occurred-at event))
                   (map? (:data event))
                   (or (nil? (:metadata event)) (map? (:metadata event))))
      (throw (ex-info "Invalid event proposal" {:event event})))
    (when (or (contains-keyword-value? (:data event))
              (contains-keyword-value? (:metadata event)))
      (throw (ex-info "Keyword values are not valid stored event data"
                      {:reason :lossy-json-value :event event}))))
  (doseq [message messages]
    (when-not (and (uuid? (:message-id message))
                   (uuid? (:causation-id message))
                   (uuid? (:correlation-id message))
                   (keyword? (:message-type message))
                   (keyword? (:recipient message))
                   (map? (:payload message)))
      (throw (ex-info "Invalid message proposal" {:message message})))
    (when (contains-keyword-value? (:payload message))
      (throw (ex-info "Keyword values are not valid message data"
                      {:reason :lossy-json-value :payload (:payload message)})))))

(defrecord MemoryStore [state clock]
  component/Lifecycle
  (start [this] (assoc this :state (atom {:log [] :ledger {} :outbox []})))
  (stop [this] (assoc this :state nil))

  port/EventStore
  (command-result [_ stream-id command]
    (validate-command! command)
    (when-let [prior (get-in @state [:ledger (:command/id command)])]
      (when-not (= (request-identity stream-id command) (:identity prior))
        (throw (ex-info "Command id already identifies another request"
                        {:reason :command-id-collision
                         :command/id (:command/id command)})))
      (:events prior)))

  (read-stream [_ stream-id]
    (->> (:log @state)
         (filter #(= stream-id (:stream/id %)))
         (sort-by :stream/version)
         vec))

  (stream-version [this stream-id]
    (->> (port/read-stream this stream-id)
         (map :stream/version)
         (apply max 0)))

  (read-since [_ position]
    (->> (:log @state)
         (filter #(> (:event/position %) position))
         (sort-by :event/position)
         vec))

  (commit-command [_ stream-id expected-version command events messages]
    (validate-command! command)
    (validate-outcome! events messages)
    (let [result (volatile! nil)
          identity (request-identity stream-id command)
          recorded-at (port/now clock)]
      (swap! state
             (fn [{:keys [log ledger] :as current}]
               (if-let [prior (get ledger (:command/id command))]
                 (do
                   (when-not (= identity (:identity prior))
                     (throw (ex-info "Command id already identifies another request"
                                     {:reason :command-id-collision
                                      :command/id (:command/id command)})))
                   (vreset! result (:events prior))
                   current)
                 (let [duplicate-event (some (set (map :event/id log))
                                             (map :event/id events))
                       duplicate-message (some (set (map :message-id (:outbox current)))
                                               (map :message-id messages))
                       history (filter #(= stream-id (:stream/id %)) log)
                       actual (apply max 0 (map :stream/version history))]
                   (when duplicate-event
                     (throw (ex-info "Event id already identifies another fact"
                                     {:reason :duplicate-event-id
                                      :event/id duplicate-event})))
                   (when duplicate-message
                     (throw (ex-info "Message id already identifies another envelope"
                                     {:reason :duplicate-message-id
                                      :message-id duplicate-message})))
                   (when-not (= expected-version actual)
                     (throw (ex-info "Concurrent modification of stream"
                                     {:reason :concurrent-modification
                                      :stream/id stream-id
                                      :expected-version expected-version
                                      :actual-version actual})))
                   (let [end (count log)
                         recorded (mapv (fn [i event]
                                          (-> event
                                              (assoc :event/position (+ end 1 i)
                                                     :stream/id stream-id
                                                     :stream/version (+ actual 1 i))
                                              (update :metadata assoc
                                                      :recorded-at recorded-at)))
                                        (range) events)]
                     (vreset! result recorded)
                     (-> current
                         (update :log into recorded)
                         (update :outbox into messages)
                         (assoc-in [:ledger (:command/id command)]
                                   {:identity identity :events recorded})))))))
      @result))

  port/Outbox
  (pending [_] (:outbox @state)))

(defn store [] (map->MemoryStore {}))
