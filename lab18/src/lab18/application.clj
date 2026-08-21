(ns lab18.application
  "The driving command use case: load, decide, identify, append."
  (:require [lab18.store :as store]
            [lab18.truck :as truck]))

(defn- identify-events
  [proposals command expected-version rules gen-id occurred-at]
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
                        :correlation-id (:correlation-id command)
                        :decision-stream-version expected-version
                        :rules-version rules))))
        proposals))

(defn handle
  "Execute one valid command under an explicit retained rules version.

  `recorded-at` stands in for the persistence adapter's authoritative clock."
  [log stream-id command rules gen-id occurred-at recorded-at]
  (let [history   (store/stream log stream-id)
        version   (or (:stream/version (peek history)) 0)
        state     (truck/replay history)
        proposals (truck/decide command state rules)
        events    (identify-events proposals command version rules gen-id occurred-at)]
    (store/append log stream-id version recorded-at events)))
