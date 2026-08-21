(ns lab11.runner
  "Wiring: the store, the domain, and the process manager, plus a clock.

  The clock is an argument for the same reason an id generator is (lab 4).
  A process manager is the first thing in these labs whose behaviour depends
  on time. The clock value makes that decision deterministic; scheduled calls
  to `run-once` provide the separate timer wake-up."
  (:require [lab11.process :as process]
            [lab11.store :as store]
            [lab11.truck :as truck]))

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
        version   (store/current-version history stream-id)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events gen-id now command proposals)]
    (store/append log stream-id version events)))

(defn dispatch
  "Run `command` unless the log shows it already ran (lab 10).

  The donor's expected business refusal is converted to silence. Other
  exceptions are bugs, invalid semantics, or infrastructure failures and must
  remain visible."
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

(defn- correlations-in
  [events]
  (->> events
       (keep #(get-in % [:metadata :correlation-id]))
       distinct
       vec))

(defn- active-correlations-in
  "Conversations that still need an event or a timer to move forward."
  [log]
  (->> (correlations-in log)
       (filter (fn [correlation-id]
                 (-> (store/correlated log correlation-id)
                     process/replay
                     process/active?)))
       vec))

(defn run-once
  "React to new events and poll active conversations for timer wake-ups.

  Unlike lab 10's reactor, this one does not react to events one at a time. It
  re-folds each awakened conversation because the next step depends on the
  whole process state. Active conversations are included even when the event
  batch is empty; otherwise a timeout could never fire after checkpointing."
  [log checkpoint gen-id now donor]
  (let [batch        (store/since log checkpoint)
        correlations (->> (concat (correlations-in batch)
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
