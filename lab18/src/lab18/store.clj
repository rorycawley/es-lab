(ns lab18.store
  "The in-memory append-only log.

  The application owns event identity, occurrence time and causal context.
  Persistence assigns stream coordinates, global position and transaction
  time (`:recorded-at`). A real adapter obtains that last value from the
  database rather than trusting a client clock.")

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
  "Append identified `events` at `expected-version`.

  `recorded-at` represents an authoritative persistence clock in this
  in-memory adapter. A production compare-and-append must perform the version
  check, timestamp assignment and write atomically."
  [log stream-id expected-version recorded-at events]
  (when-not (inst? recorded-at)
    (throw (ex-info "Invalid recorded-at instant"
                    {:recorded-at recorded-at})))
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
                                :stream/version (+ actual 1 i)
                                :metadata (assoc (:metadata event)
                                                 :recorded-at recorded-at)))
                       events))))
