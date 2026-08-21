(ns lab11.runner
  "Wiring: the store, the domain, and the process manager, plus a clock.

  The clock is an argument for the same reason an id generator is (lab 4).
  A process manager is the first thing in these labs whose behaviour depends
  on time, and a test that cannot move time cannot test a timeout."
  (:require [lab11.process :as process]
            [lab11.store :as store]
            [lab11.truck :as truck]))

(defn handle
  "Run one command against the truck it addresses (lab 8)."
  [log gen-id now command]
  (let [stream-id (get-in command [:data :truck-id])
        history   (store/stream log stream-id)
        version   (store/current-version log stream-id)
        state     (truck/replay history)
        events    (truck/decide command state)]
    (store/append log stream-id version gen-id now command events)))

(defn dispatch
  "Run `command` unless the log shows it already ran (lab 10).

  A refused command is caught, not propagated. A refusal records nothing
  (lab 5), so from the process manager's point of view it is indistinguishable
  from silence — which is what the timeout exists to handle."
  [log gen-id now command]
  (if (store/caused-by? log (:command/id command))
    log
    (try
      (handle log gen-id now command)
      (catch clojure.lang.ExceptionInfo _refused
        log))))

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

(defn run-once
  "React to everything appended since `checkpoint`.

  Unlike lab 10's reactor, this one does not react to events one at a time.
  It gathers the conversations the new events belong to and re-folds each,
  because a process manager's next step depends on the whole conversation, not
  on the message that woke it up."
  [log checkpoint gen-id now donor]
  (let [batch (store/since log checkpoint)
        log'  (reduce (fn [l cid] (advance-process l gen-id now cid donor))
                      log
                      (correlations-in batch))]
    {:log        log'
     :checkpoint (->> batch (map :event/position) (apply max checkpoint))}))

(defn run-until-quiet
  "Keep running passes until one appends nothing."
  ([log checkpoint gen-id now donor]
   (run-until-quiet log checkpoint gen-id now donor 100))
  ([log checkpoint gen-id now donor max-passes]
   (loop [log log, checkpoint checkpoint, passes 0]
     (let [{log' :log checkpoint' :checkpoint} (run-once log checkpoint gen-id now donor)]
       (cond
         (= log log') {:log log' :checkpoint checkpoint' :passes passes}
         (>= passes max-passes) (throw (ex-info "Process did not settle"
                                                {:passes passes}))
         :else (recur log' checkpoint' (inc passes)))))))
