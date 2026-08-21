(ns lab8.handler
  "The loop. Four steps, and this is the only namespace that knows all of
  them: the domain does not know there is a store, and the store does not
  know what any event means."
  (:require [lab8.store :as store]
            [lab8.truck :as truck]))

(defn- identify-events
  [gen-id proposals]
  (mapv (fn [proposal]
          (let [event-id (gen-id)]
            (when-not (uuid? event-id)
              (throw (ex-info "Invalid event id"
                              {:event/id event-id})))
            (assoc proposal :event/id event-id)))
        proposals))

(defn handle
  "Run one command against one stream.

  1. read   the stream
  2. fold   it into state
  3. decide what happened
  4. identify and append the result, on the condition the stream has not moved

  Returns the new log."
  [log gen-id stream-id command]
  (let [history (store/stream log stream-id)
        version (store/current-version history stream-id)
        state   (truck/replay history)
        proposals (truck/decide command state)
        events  (identify-events gen-id proposals)]
    (store/append log stream-id version events)))
