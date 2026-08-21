(ns lab10.store
  "The append-only log, as of lab 9. Identified events carry the command that
  caused them, so a consumer can ask whether it has already acted.")

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

  This is a deliberately narrow deduplication shortcut for commands known to
  produce events. A general handler needs a command ledger because a
  successful no-op leaves no causation id in the event log."
  [log command-id]
  (boolean (some #(= command-id (get-in % [:metadata :causation-id])) log)))

(defn append
  "Model appending identified `events` to `stream-id` if the supplied log is
  still at `expected-version`.

  Identity and causation already exist and are preserved. This in-memory store
  assigns stream versions and global positions; a production store must make
  those assignments and the batch write atomic."
  [log stream-id expected-version events]
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
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
