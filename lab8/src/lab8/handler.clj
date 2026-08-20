(ns lab8.handler
  "The loop. Four steps, and this is the only namespace that knows all of
  them: the domain does not know there is a store, and the store does not
  know what any event means."
  (:require [lab8.store :as store]
            [lab8.truck :as truck]))

(defn handle
  "Run one command against one stream.

  1. read   the stream
  2. fold   it into state
  3. decide what happened
  4. append the result, on the condition the stream has not moved

  Returns the new log."
  [log gen-id stream-id command]
  (let [history (store/stream log stream-id)
        version (store/current-version log stream-id)
        state   (truck/replay history)
        events  (truck/decide command state)]
    (store/append log stream-id version gen-id events)))
