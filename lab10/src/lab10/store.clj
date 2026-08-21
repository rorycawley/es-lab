(ns lab10.store
  "The append-only log, as of lab 9, with one addition: an event records the
  command that caused it, so a consumer can ask whether it has already acted.")

(defn stream
  "The history of one truck (lab 7)."
  [log stream-id]
  (->> log
       (filter #(= stream-id (:stream/id %)))
       (sort-by :stream/version)
       vec))

(defn current-version
  [log stream-id]
  (->> (stream log stream-id)
       (map :stream/version)
       (apply max 0)))

(defn last-position
  [log]
  (->> log (map :event/position) (apply max 0)))

(defn since
  "Every event appended after global `position` (lab 9)."
  [log position]
  (->> log
       (filter #(> (:event/position %) position))
       (sort-by :event/position)
       vec))

(defn caused-by?
  "Has any event in the log already been caused by this command?

  This is the whole of idempotency for a reactor: the causation id is written
  into the events a command produced, so the question can be answered from the
  log itself, with no separate table of processed commands."
  [log command-id]
  (boolean (some #(= command-id (get-in % [:metadata :causation-id])) log)))

(defn append
  "Append `events` to `stream-id` if it is still at `expected-version`.

  The store stamps identity, stream, version, position — and the causation id,
  which is the id of the command that produced these events (lab 4)."
  [log stream-id expected-version gen-id command-id events]
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
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)
                                :metadata {:causation-id command-id}))
                       events))))
