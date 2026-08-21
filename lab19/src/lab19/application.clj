(ns lab19.application
  "The driving command use case around the Postgres adapter."
  (:require [lab19.store :as store]
            [lab19.truck :as truck]))

(defn- identify-events
  [proposals command gen-id occurred-at]
  (when-not (inst? occurred-at)
    (throw (ex-info "Invalid occurred-at instant"
                    {:event/occurred-at occurred-at})))
  (mapv (fn [proposal]
          (let [event-id (gen-id)]
            (when-not (uuid? event-id)
              (throw (ex-info "Invalid event id" {:event/id event-id})))
            (-> proposal
                (assoc :event/id event-id
                       :event/occurred-at occurred-at)
                (update :metadata assoc
                        :causation-id (:command/id command)
                        :correlation-id (:correlation-id command)))))
        proposals))

(defn handle
  "Load one exact history, decide, identify facts, and compare-and-append."
  [ds stream-id command gen-id occurred-at]
  (let [history   (store/stream ds stream-id)
        version   (or (:stream/version (peek history)) 0)
        state     (truck/replay history)
        proposals (truck/decide command state)
        events    (identify-events proposals command gen-id occurred-at)]
    (store/append ds stream-id version events)))
