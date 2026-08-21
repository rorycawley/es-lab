(ns lab21.core.contract
  "Which outgoing messages a fact produces (lab 12), as a pure function.

  Takes an event and returns addressed message proposals. No message id or
  timestamp exists yet; the shell creates the complete envelope at send time."
  (:require [clojure.string :as str]))

(defn announce
  [event]
  (case (:event/type event)
    :stock-depleted
    (let [payload {:fact-id  (str (:event/id event))
                   :truck-id (str (:stream/id event))
                   :flavour  (name (get-in event [:data :flavour]))}]
      (when-not (uuid? (:event/id event))
        (throw (ex-info "Invalid event id"
                        {:event/id (:event/id event)})))
      [{:message-type :flavour-unavailable :recipient :customer-app :payload payload}
       {:message-type :restock-required    :recipient :purchasing   :payload payload}])

    :truck-loaded []
    :flavour-sold []

    (throw (ex-info "Unknown event type"
                    {:event/type (:event/type event)}))))

(defn describe
  "A one-line rendering, for the demo. Pure: it returns a string, prints nothing."
  [message]
  (str/join " " [(name (:recipient message))
                 "←"
                 (name (:message-type message))
                 (str "(" (get-in message [:payload :flavour]) ")")]))
