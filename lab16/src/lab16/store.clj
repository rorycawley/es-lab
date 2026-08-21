(ns lab16.store
  "The in-memory append-only log.

  Every design in this lab uses the same storage mechanics so the comparison
  can isolate one structural choice: which facts share a stream and therefore
  a version. Real aggregate design must also account for invariants, authority,
  lifetime, workflows and workload.")

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

  Identity, occurrence time and causal context already exist. Persistence
  preserves them and assigns only stream versions and global positions. A
  production compare-and-append must be atomic."
  [log stream-id expected-version events]
  (let [actual (current-version log stream-id)
        end    (last-position log)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:reason           :concurrent-modification
                       :stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (into log
          (map-indexed (fn [i event]
                         (assoc event
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
