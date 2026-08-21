(ns lab12.store
  "Lab 11's log, with the two process-manager helpers dropped — and nothing
  whatever added for publishing. That is this lab's first point.

  Publishing needs a durable, ordered record of what happened, with a cursor
  a relay can resume from. An event-sourced store already is one.")

(defn stream
  [log stream-id]
  (->> log
       (filter #(= stream-id (:stream/id %)))
       (sort-by :stream/version)
       vec))

(defn current-version
  [log stream-id]
  (->> (stream log stream-id) (map :stream/version) (apply max 0)))

(defn last-position
  [log]
  (->> log (map :event/position) (apply max 0)))

(defn since
  [log position]
  (->> log
       (filter #(> (:event/position %) position))
       (sort-by :event/position)
       vec))

(defn append
  [log stream-id expected-version gen-id now command events]
  (let [actual (current-version log stream-id)
        end    (last-position log)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (into log
          (map-indexed (fn [i event]
                         (assoc event
                                :event/id (gen-id)
                                :event/occurred-at now
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)
                                :metadata {:causation-id   (:command/id command)
                                           :correlation-id (:correlation-id command)}))
                       events))))
