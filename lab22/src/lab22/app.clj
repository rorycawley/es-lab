(ns lab22.app
  "The use-case surface and imperative shell. It receives validated internal
  commands; untrusted-message validation remains in the driving adapter."
  (:require [lab22.core.contract :as contract]
            [lab22.core.policy :as policy]
            [lab22.core.truck :as truck]
            [lab22.port :as port]))

(defn stock [{:keys [store]} truck-id]
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
  "Return a prior outcome or decide and atomically commit a new one."
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
        commands (policy/react-to-all batch)
        applied  (reduce #(conj %1 (handle app (get-in %2 [:data :truck-id]) %2)) [] commands)]
    {:checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   (vec commands)
     :events     (vec (apply concat applied))}))
