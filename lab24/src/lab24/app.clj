(ns lab24.app
  "The use-case surface and imperative shell. It receives validated internal
  commands; untrusted-message validation remains in the driving adapter."
  (:require [lab24.core.contract :as contract]
            [lab24.core.policy :as policy]
            [lab24.core.truck :as truck]
            [lab24.port.driven :as driven]))

(defn stock [{:keys [store]} truck-id]
  (truck/stock (truck/replay (driven/read-stream store truck-id))))

(defn operations [{:keys [store]} truck-id]
  (truck/operations (truck/replay (driven/read-stream store truck-id))))

(defn- identify [ids occurred-at command event]
  (-> event
      (assoc :event/id (driven/new-id ids) :event/occurred-at occurred-at)
      (update :metadata assoc
              :causation-id (:command/id command)
              :correlation-id (:correlation-id command)
              :actor (:command/actor command))))

(defn- envelope [ids event message]
  (assoc message :message-id (driven/new-id ids)
         :causation-id (:event/id event)
         :correlation-id (get-in event [:metadata :correlation-id])))

(defn handle
  "Return a prior outcome or decide and atomically commit a new one."
  [{:keys [store clock ids]} truck-id command]
  (or (driven/command-result store truck-id command)
      (let [history  (driven/read-stream store truck-id)
            version  (or (:stream/version (peek history)) 0)
            state    (truck/replay history)
            decided  (truck/decide command state)
            occurred (driven/now clock)
            events   (mapv #(identify ids occurred command %) decided)
            facts    (mapv #(assoc % :stream/id truck-id) events)
            messages (into [] (mapcat (fn [event]
                                        (map #(envelope ids event %)
                                             (contract/announce event)))
                                      facts))]
        (driven/commit-command store truck-id version command events messages))))

(defn react
  "Read new facts, ask the policy, and dispatch its requests."
  [{:keys [store] :as app} checkpoint]
  (let [batch    (driven/read-since store checkpoint)
        commands (policy/react-to-all batch)
        applied  (doall (for [command commands]
                          (handle app (get-in command [:data :truck-id]) command)))]
    {:checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   (vec commands)
     :events     (vec (apply concat applied))}))
