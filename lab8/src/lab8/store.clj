(ns lab8.store
  "The store: an append-only log of events, divided into streams.

  Lab 7's material, with one change — `append` takes a batch rather than a
  single event, because lab 5 established that a command can produce several
  and they must land together or not at all.")

(defn stream
  "The history of one thing, in the order it happened."
  [log stream-id]
  (->> log
       (filter #(= stream-id (:stream/id %)))
       (sort-by :stream/version)
       vec))

(defn current-version
  "The version of the last event in a stream, or 0 if it does not exist yet."
  [log stream-id]
  (->> (stream log stream-id)
       (map :stream/version)
       (apply max 0)))

(defn append
  "Model appending identified `events` to `stream-id`, on the condition that
  the supplied log is still at `expected-version`.

  This immutable implementation returns the whole new log or throws before
  returning one. A real store must enforce the condition and batch insert in
  one atomic transaction.

  Event identity already exists and is preserved. The store assigns the
  stream and consecutive versions it owns."
  [log stream-id expected-version events]
  (let [actual (current-version log stream-id)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (into log
          (map-indexed (fn [i event]
                         (assoc event
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
