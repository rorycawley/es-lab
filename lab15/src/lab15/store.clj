(ns lab15.store
  "The in-memory append-only log.

  Nothing in this lab deletes from it, edits it, or adds a way to. That is the
  constraint the whole design has to work around.")

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

  This is the narrow lab 10 shortcut for commands guaranteed to emit an
  event, not a general command ledger."
  [log command-id]
  (boolean (some #(= command-id (get-in % [:metadata :causation-id])) log)))

(defn append
  "Model appending identified `events` at `expected-version`.

  The application has already assigned identity, occurrence time and causal
  context. This persistence boundary preserves them and assigns only stream
  versions and global positions. A production compare-and-append must be
  atomic."
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
