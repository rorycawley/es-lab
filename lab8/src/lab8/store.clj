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
  "Append `events` to `stream-id`, but only if it is still at
  `expected-version`. All of them land, or none do.

  The store stamps what it owns: an identity for each event, the stream it
  belongs to, and consecutive versions. `gen-id` supplies the identities —
  minting one is an effect, so it comes in as an argument (lab 4)."
  [log stream-id expected-version gen-id events]
  (let [actual (current-version log stream-id)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (into log
          (map-indexed (fn [i event]
                         (assoc event
                                :event/id (gen-id)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
