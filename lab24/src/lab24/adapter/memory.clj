(ns lab24.adapter.memory
  "A working persistence fake whose one atom preserves the production
  transaction across events, command ledger and outbox."
  (:require [com.stuartsierra.component :as component]
            [lab24.port.driven :as driven]))

(defn- keyword-value? [x]
  (cond (keyword? x) true
        (map? x) (boolean (some keyword-value? (vals x)))
        (coll? x) (boolean (some keyword-value? x))
        :else false))

(defn- identity-of [stream-id command]
  {:stream-id stream-id :command-type (:command/type command)
   :actor (:command/actor command) :data (:data command)})

(defn- actor? [actor]
  (and (map? actor)
       (= #{:type :id} (set (keys actor)))
       (#{"user" "system"} (:type actor))
       (string? (:id actor))
       (not-empty (:id actor))))

(defn- validate! [command events messages]
  (when-not (and (uuid? (:command/id command))
                 (uuid? (:correlation-id command))
                 (actor? (:command/actor command))
                 (map? (:data command))
                 (not (keyword-value? (:data command))))
    (throw (ex-info "Invalid command" {:command command})))
  (when-not (= (count events) (count (distinct (map :event/id events))))
    (throw (ex-info "Duplicate event ids" {:reason :duplicate-event-id})))
  (when-not (= (count messages) (count (distinct (map :message-id messages))))
    (throw (ex-info "Duplicate message ids" {:reason :duplicate-message-id})))
  (doseq [event events]
    (when-not (and (uuid? (:event/id event)) (keyword? (:event/type event))
                   (inst? (:event/occurred-at event)) (map? (:data event))
                   (map? (:metadata event))
                   (= (:command/id command) (get-in event [:metadata :causation-id]))
                   (= (:correlation-id command) (get-in event [:metadata :correlation-id]))
                   (= (:command/actor command) (get-in event [:metadata :actor]))
                   (not (keyword-value? (:data event)))
                   (not (keyword-value? (:metadata event))))
      (throw (ex-info "Invalid event proposal" {:event event}))))
  (doseq [message messages]
    (when-not (and (uuid? (:message-id message)) (uuid? (:causation-id message))
                   (uuid? (:correlation-id message)) (keyword? (:message-type message))
                   (keyword? (:recipient message)) (map? (:payload message))
                   (not (keyword-value? (:payload message))))
      (throw (ex-info "Invalid message proposal" {:message message})))))

(defrecord MemoryStore [state clock]
  component/Lifecycle
  (start [this] (assoc this :state (atom {:log [] :ledger {} :outbox []})))
  (stop [this] (assoc this :state nil))

  driven/EventStore
  (command-result [_ stream-id command]
    (validate! command [] [])
    (when-let [prior (get-in @state [:ledger (:command/id command)])]
      (when-not (= (identity-of stream-id command) (:identity prior))
        (throw (ex-info "Command id already identifies another request"
                        {:reason :command-id-collision})))
      (:events prior)))
  (read-stream [_ stream-id]
    (->> (:log @state) (filter #(= stream-id (:stream/id %)))
         (sort-by :stream/version) vec))
  (stream-version [this stream-id]
    (apply max 0 (map :stream/version (driven/read-stream this stream-id))))
  (read-since [_ position]
    (->> (:log @state) (filter #(> (:event/position %) position))
         (sort-by :event/position) vec))
  (commit-command [_ stream-id expected-version command events messages]
    (validate! command events messages)
    (let [result (volatile! nil)
          identity (identity-of stream-id command)
          recorded-at (driven/now clock)]
      (swap! state
             (fn [{:keys [log ledger outbox] :as current}]
               (if-let [prior (get ledger (:command/id command))]
                 (do (when-not (= identity (:identity prior))
                       (throw (ex-info "Command id already identifies another request"
                                       {:reason :command-id-collision})))
                     (vreset! result (:events prior))
                     current)
                 (let [event-collision (some (set (map :event/id log)) (map :event/id events))
                       message-collision (some (set (map :message-id outbox))
                                               (map :message-id messages))
                       actual (apply max 0 (map :stream/version
                                                (filter #(= stream-id (:stream/id %)) log)))]
                   (when event-collision
                     (throw (ex-info "Event id already identifies another fact"
                                     {:reason :duplicate-event-id})))
                   (when message-collision
                     (throw (ex-info "Message id already identifies another envelope"
                                     {:reason :duplicate-message-id})))
                   (when-not (= expected-version actual)
                     (throw (ex-info "Concurrent modification of stream"
                                     {:reason :concurrent-modification
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

  driven/Outbox
  (pending [_] (:outbox @state)))

(defn store [] (map->MemoryStore {}))
