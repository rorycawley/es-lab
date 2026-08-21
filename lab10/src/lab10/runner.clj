(ns lab10.runner
  "Wiring. The only namespace that knows about the store, the domain and the
  policy at once — the same arrangement as lab 8's handler, one level up."
  (:require [lab10.policy :as policy]
            [lab10.store :as store]
            [lab10.truck :as truck]))

(defn- identify-events
  [gen-id command-id proposals]
  (mapv (fn [proposal]
          (let [event-id (gen-id)]
            (when-not (uuid? event-id)
              (throw (ex-info "Invalid event id"
                              {:event/id event-id})))
            (-> proposal
                (assoc :event/id event-id)
                (update :metadata assoc :causation-id command-id))))
        proposals))

(defn handle
  "Run one command against the truck it addresses (lab 8).

  The stream is taken from the command's address (lab 2) — the handler does
  not need to be told separately which history to read."
  [log gen-id command]
  (let [stream-id (get-in command [:data :truck-id])
        history   (store/stream log stream-id)
        version   (store/current-version history stream-id)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events gen-id (:command/id command) proposals)]
    (store/append log stream-id version events)))

(defn dispatch
  "Run `command` unless the log shows it has already been run.

  For this policy, whose command always records an event, the causation id is
  enough to recognise a redelivery. The general zero-event case needs the
  command ledger introduced in lab 20."
  [log gen-id command]
  (if (store/caused-by? log (:command/id command))
    log
    (handle log gen-id command)))

(defn run-once
  "Read what has arrived since `checkpoint`, react to it, dispatch the result.

  Returns the new log and the new checkpoint.

  The checkpoint moves to the last position *read*, not to the end of the log.
  Those differ, because dispatching appends: checkpointing at the new end
  would silently skip anything another writer landed in between."
  [log checkpoint gen-id]
  (let [batch    (store/since log checkpoint)
        commands (policy/react-to-all batch)
        log'     (reduce (fn [l c] (dispatch l gen-id c)) log commands)]
    {:log        log'
     :checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   commands}))

(defn run-until-quiet
  "Keep reacting until a pass produces no commands.

  This terminates only because the policy has no opinion about the events its
  own commands produce. A policy that reacted to its own output would loop
  forever; `max-passes` is here to make that failure visible in a test rather
  than hanging one."
  ([log checkpoint gen-id] (run-until-quiet log checkpoint gen-id 100))
  ([log checkpoint gen-id max-passes]
   (loop [log log, checkpoint checkpoint, passes 0]
     (let [{:keys [log commands] :as result} (run-once log checkpoint gen-id)
           next-passes (inc passes)]
       (cond
         (empty? commands) (assoc result :passes passes)
         (> next-passes max-passes) (throw (ex-info "Policy did not settle"
                                                    {:passes next-passes
                                                     :log-size (count log)}))
         :else (recur log (:checkpoint result) next-passes))))))
