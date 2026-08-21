(ns lab14.runner
  "Wiring: the store, the domain, and the process manager, plus a clock.

  Compensation needs no special dispatch path: it is a business command like
  any other. The application still allocates fact identity and attaches
  causation and correlation before the persistence boundary."
  (:require [lab14.process :as process]
            [lab14.store :as store]
            [lab14.truck :as truck]))

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

(defn handle
  "Run one command against the truck it addresses (lab 8)."
  [log gen-id now command]
  (let [stream-id (get-in command [:data :truck-id])
        history   (store/stream log stream-id)
        version   (store/current-version log stream-id)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events gen-id now command proposals)]
    (store/append log stream-id version events)))

(defn dispatch
  "Run `command` unless the log shows it already ran (lab 10).

  The donor's expected `:not-enough-to-spare` refusal records nothing and is
  converted to silence. The load and return refusals are facts. Every other
  exception remains visible because it represents invalid semantics, a bug,
  or an infrastructure failure rather than this one expected outcome."
  [log gen-id now command]
  (if (store/caused-by? log (:command/id command))
    log
    (try
      (handle log gen-id now command)
      (catch clojure.lang.ExceptionInfo failure
        (if (= :not-enough-to-spare (:reason (ex-data failure)))
          log
          (throw failure))))))

(defn advance-process
  "Fold one conversation, decide what it needs next, and dispatch that."
  [log gen-id now correlation-id donor]
  (let [state    (process/replay (store/correlated log correlation-id))
        commands (process/decide state correlation-id donor now)]
    (reduce (fn [l c] (dispatch l gen-id now c)) log commands)))

(defn- transfer-correlations-in
  "Conversations started by the event this process subscribes to."
  [events]
  (->> events
       (filter #(= :stock-depleted (:event/type %)))
       (keep #(get-in % [:metadata :correlation-id]))
       distinct
       vec))

(defn- active-correlations-in
  "Conversations that still need an event or timer to move forward."
  [log]
  (->> (transfer-correlations-in log)
       (filter (fn [correlation-id]
                 (-> (store/correlated log correlation-id)
                     process/replay
                     process/active?)))
       vec))

(defn run-once
  "React to new events and poll active conversations for timer wake-ups.

  A process manager re-folds each awakened conversation because its next step
  depends on the whole history. Active conversations are included even when
  no new fact exists; otherwise the donor timeout could never fire after the
  triggering batch had been checkpointed."
  [log checkpoint gen-id now donor]
  (let [batch        (store/since log checkpoint)
        correlations (->> (concat (transfer-correlations-in batch)
                                  (active-correlations-in log))
                          distinct)
        log'         (reduce (fn [l cid]
                               (advance-process l gen-id now cid donor))
                             log
                             correlations)]
    {:log        log'
     :checkpoint (->> batch (map :event/position) (apply max checkpoint))}))

(defn run-until-quiet
  "Keep running passes until one appends nothing."
  ([log checkpoint gen-id now donor]
   (run-until-quiet log checkpoint gen-id now donor 100))
  ([log checkpoint gen-id now donor max-passes]
   (loop [log log, checkpoint checkpoint, passes 0]
     (let [{log' :log checkpoint' :checkpoint} (run-once log checkpoint gen-id now donor)
           next-passes (inc passes)]
       (cond
         (= log log') {:log log' :checkpoint checkpoint' :passes passes}
         (> next-passes max-passes) (throw (ex-info "Process did not settle"
                                                    {:passes next-passes}))
         :else (recur log' checkpoint' next-passes))))))
