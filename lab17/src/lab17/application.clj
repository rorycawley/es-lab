(ns lab17.application
  "The driving use case: load, decide, identify, append.

  The domain proposes facts without performing effects. This boundary obtains
  event identity and time before persistence; the store only assigns storage
  coordinates."
  (:require [lab17.store :as store]
            [lab17.truck :as truck]))

(defn- identify-events
  [proposals command gen-id now]
  (mapv (fn [proposal]
          (let [event-id (gen-id)]
            (when-not (uuid? event-id)
              (throw (ex-info "Invalid event id" {:event/id event-id})))
            (-> proposal
                (assoc :event/id event-id
                       :event/occurred-at now)
                (update :metadata assoc
                        :causation-id (:command/id command)
                        :correlation-id (:correlation-id command)))))
        proposals))

(defn handle
  "Execute one valid truck command against the supplied log."
  [log stream-id command gen-id now]
  (let [history   (store/stream log stream-id)
        version   (or (:stream/version (peek history)) 0)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events proposals command gen-id now)]
    (store/append log stream-id version events)))
