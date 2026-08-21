(ns lab18.store
  "The log, unchanged since lab 11.

  Every question this lab asks was already answerable on the day lab 11 was
  written. Nothing needed adding — which is the point: the ability to ask
  what was true last Tuesday is a consequence of keeping events, not a
  feature you build.")

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
  "Has any event already been caused by this command? (lab 10)"
  [log command-id]
  (boolean (some #(= command-id (get-in % [:metadata :causation-id])) log)))

(defn append
  "Append the events `command` produced to `stream-id`.

  The store stamps identity, position, stream coordinates, the occurrence time
  from the injected clock, and the two ids that place the event in a chain:
  causation (this command) and correlation (this conversation)."
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
