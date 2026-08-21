(ns lab14.store
  "The in-memory append-only log. Compensation needs nothing special from the
  store: its outcomes are facts appended through the ordinary path.")

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

(defn correlated
  "Every event belonging to one conversation, in order.

  This is the whole history of a process — and it spans streams, which is why
  a correlation id can answer questions `:stream/id` cannot."
  [log correlation-id]
  (->> log
       (filter #(= correlation-id (get-in % [:metadata :correlation-id])))
       (sort-by :event/position)
       vec))

(defn caused-by?
  "Has any event already been caused by this command?

  This is lab 10's narrow shortcut for commands guaranteed to record an
  event, not a general command ledger."
  [log command-id]
  (boolean (some #(= command-id (get-in % [:metadata :causation-id])) log)))

(defn append
  "Model appending identified `events` to `stream-id`.

  Identity, occurrence time, causation and correlation already exist and are
  preserved. This store assigns only stream versions and global positions; a
  production store must make the compare-and-append atomic."
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
