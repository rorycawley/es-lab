(ns lab21.app
  "The application layer — the **imperative shell**.

  Read this looking for business logic. There is none. Every function here has
  the same shape:

      read something          ← a port
      call a pure function    ← the core
      write something         ← a port

  That shape is the whole discipline, and its thinness is the measure. The
  moment a conditional appears here that is not about I/O, a rule has leaked
  out of the core into coordination code and lost direct pure-function tests.

  Ports-and-adapters calls this the application or service layer. It is the
  only code that knows both worlds: it depends on `port`, never on any
  `adapter`, and it hands the core plain values."
  (:require [lab21.core.contract :as contract]
            [lab21.core.policy :as policy]
            [lab21.core.truck :as truck]
            [lab21.port :as port]))

(defn stock
  [{:keys [store]} truck-id]
  (truck/replay (port/read-stream store truck-id)))

(defn- identify [ids occurred-at command event]
  (-> event
      (assoc :event/id (port/new-id ids) :event/occurred-at occurred-at)
      (update :metadata assoc
              :causation-id (:command/id command)
              :correlation-id (:correlation-id command))))

(defn- envelope [ids event message]
  (assoc message :message-id (port/new-id ids)
         :causation-id (:event/id event)
         :correlation-id (get-in event [:metadata :correlation-id])))

(defn handle
  "Read, decide, and atomically commit one complete command outcome."
  [{:keys [store clock ids]} truck-id command]
  (or (port/command-result store truck-id command)
      (let [history  (port/read-stream store truck-id)
            version  (or (:stream/version (peek history)) 0)
            state    (truck/replay history)
            decided  (truck/decide command state)
            occurred (port/now clock)
            events   (mapv #(identify ids occurred command %) decided)
            facts    (mapv #(assoc % :stream/id truck-id) events)
            messages (into [] (mapcat (fn [event]
                                        (map #(envelope ids event %)
                                             (contract/announce event)))
                                      facts))]
        (port/commit-command store truck-id version command events messages))))

(defn react
  "Read new facts, ask the policy, and dispatch its requests."
  [{:keys [store] :as app} checkpoint]
  (let [batch    (port/read-since store checkpoint)
        commands (policy/react-to-all batch)               ; core
        applied  (doall (for [command commands]
                          (handle app (get-in command [:data :truck-id]) command)))]
    {:checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   (vec commands)
     :events     (vec (apply concat applied))}))
