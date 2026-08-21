(ns lab16.contention
  "Measuring what a boundary costs.

  Optimistic concurrency (lab 7) rejects an append whose expected version has
  moved. So the question 'how much will this design contend?' has a precise
  structural input: **how many concurrent writers target the same stream?**

  The observed conflict rate also depends on overlap, transaction duration,
  retry policy and traffic. This namespace deliberately holds overlap fixed
  so the boundary's contribution can be compared."
  (:require [lab16.store :as store]))

(defn- identify-events
  [gen-id now command proposals]
  (mapv (fn [proposal]
          (let [event-id (gen-id)]
            (when-not (uuid? event-id)
              (throw (ex-info "Invalid event id"
                              {:event/id event-id})))
            (-> proposal
                (assoc :event/id event-id
                       :event/occurred-at now)
                (update :metadata assoc
                        :causation-id (:command/id command)
                        :correlation-id (:correlation-id command)))))
        proposals))

(defn attempt
  "One writer: read a stream, decide against it, and hold the result until
  it tries to append.

  The version it read is captured *now* — which is exactly the window
  optimistic concurrency exists to police."
  [log stream-id decide replay command]
  (let [version (store/current-version log stream-id)
        state   (replay (store/stream log stream-id))
        proposals (decide command state)]
    (fn [current-log gen-id now]
      (let [events (identify-events gen-id now command proposals)]
        (store/append current-log stream-id version events)))))

(defn run-concurrently
  "Every writer reads the same log, then their appends land one after another.

  This deterministic maximum-overlap schedule isolates the boundary's effect:
  all writers decided against the same state, and the store adjudicates."
  [log attempts gen-id now]
  (reduce (fn [{:keys [log] :as acc} attempt]
            (try
              (assoc acc :log (attempt log gen-id now))
              (catch clojure.lang.ExceptionInfo e
                (if (= :concurrent-modification (:reason (ex-data e)))
                  (update acc :conflicts inc)
                  (throw e)))))
          {:log log :conflicts 0}
          attempts))
