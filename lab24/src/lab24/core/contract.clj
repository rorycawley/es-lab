(ns lab24.core.contract
  "Which outgoing messages a fact produces (lab 12), as a pure function.

  Takes an event, returns messages. No id, no timestamp, no recipient lookup —
  the shell stamps identity, exactly as lab 12 argued a delivery identity must
  be stamped at send time."
  (:require [clojure.string :as str]))

(defn announce
  [event]
  (when (= :stock-depleted (:event/type event))
    (let [payload {:fact-id  (str (:event/id event))
                   :truck-id (str (:stream/id event))
                   :flavour  (name (get-in event [:data :flavour]))}]
      [{:message-type :flavour-unavailable :recipient :customer-app :payload payload}
       {:message-type :restock-required    :recipient :purchasing   :payload payload}])))

(defn describe
  "A one-line rendering, for the demo. Pure: it returns a string, prints nothing."
  [message]
  (str/join " " [(name (:recipient message))
                 "←"
                 (name (:message-type message))
                 (str "(" (get-in message [:payload :flavour]) ")")]))
