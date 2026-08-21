(ns lab22.adapter.memory
  "The in-memory adapter — labs 1 to 18's store, wearing a protocol.

  Its purpose is not speed. It is the *second* implementation, and a port with
  one implementation is not a boundary, it is a layer of indirection with
  optimism attached. Two implementations, one application layer, one test
  suite: that is what makes the boundary real.

  It is also what lets `bb demo` run with nothing installed."
  (:require [com.stuartsierra.component :as component]
            [lab22.port :as port]))

(defrecord MemoryStore [state]
  component/Lifecycle
  (start [this] (assoc this :state (atom {:log []})))
  (stop [this] (assoc this :state nil))

  port/EventStore
  (read-stream [_ stream-id]
    (->> (:log @state)
         (filter #(= stream-id (:stream/id %)))
         (sort-by :stream/version)
         vec))

  (stream-version [this stream-id]
    (->> (port/read-stream this stream-id) (map :stream/version) (apply max 0)))

  (read-since [_ position]
    (->> (:log @state)
         (filter #(> (:event/position %) position))
         (sort-by :event/position)
         vec))

  (append [this stream-id expected-version command events]
    (let [actual (port/stream-version this stream-id)]
      (when-not (= expected-version actual)
        (throw (ex-info "Concurrent modification of stream"
                        {:stream/id stream-id :expected-version expected-version
                         :actual-version actual})))
      (let [end     (count (:log @state))
            stamped (vec (map-indexed
                          (fn [i event]
                            (assoc event
                                   :event/position (+ end 1 i)
                                   :stream/id stream-id
                                   :stream/version (+ actual 1 i)
                                   :metadata {:causation-id (:command/id command)}))
                          events))]
        (swap! state update :log into stamped)
        stamped))))

(defrecord MemoryOutbox [state]
  component/Lifecycle
  (start [this] (assoc this :state (atom [])))
  (stop [this] (assoc this :state nil))

  port/Outbox
  (enqueue [_ messages] (swap! state into messages) (vec messages))
  (pending [_] @state))

(defn store [] (map->MemoryStore {}))
(defn outbox [] (map->MemoryOutbox {}))
