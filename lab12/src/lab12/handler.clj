(ns lab12.handler
  "The event-recording application boundary established in labs 8–11."
  (:require [lab12.store :as store]
            [lab12.truck :as truck]))

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
  "Fold the addressed stream, decide, identify the proposals, and append."
  [log gen-id now command]
  (let [stream-id (get-in command [:data :truck-id])
        history   (store/stream log stream-id)
        version   (store/current-version history stream-id)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events gen-id now command proposals)]
    (store/append log stream-id version events)))
