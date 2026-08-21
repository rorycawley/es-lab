(ns lab15.application
  "The write edge: decide in plaintext, identify the resulting facts, protect
  sensitive fields, then append them.

  The domain remains pure and technology-independent. The event store never
  receives plaintext personal details, and persistence does not manufacture
  fact identity."
  (:require [lab15.domain :as domain]
            [lab15.reading :as reading]
            [lab15.store :as store]
            [lab15.vault :as vault]))

(defn- protect-card-event
  [key-vault event]
  (case (:event/type event)
    :card-issued
    (let [subject (get-in event [:data :customer-id])
          key     (vault/key-for key-vault subject)]
      (when-not key
        (throw (ex-info "No active key for subject"
                        {:subject-id subject})))
      (update-in event [:data :personal]
                 #(vault/seal key
                              (vault/personal-context subject (:event/id event))
                              %)))

    :card-cancelled event

    (throw (ex-info "Unknown card event type at protection boundary"
                    {:event/type (:event/type event)}))))

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

(defn handle-card
  [log key-vault gen-id now command]
  (let [stream-id (get-in command [:data :card-id])
        history   (store/stream log stream-id)
        version   (store/current-version log stream-id)
        state     (domain/replay-card (reading/read-all key-vault history))
        proposals (domain/decide-card command state)
        events    (->> proposals
                       (identify-events gen-id now command)
                       (mapv #(protect-card-event key-vault %)))]
    (store/append log stream-id version events)))

(defn handle-truck
  [log gen-id now command]
  (let [stream-id (get-in command [:data :truck-id])
        history   (store/stream log stream-id)
        version   (store/current-version log stream-id)
        state     (domain/replay-truck history)
        proposals (domain/decide-truck command state)
        events    (identify-events gen-id now command proposals)]
    (store/append log stream-id version events)))
