(ns lab29.platform.dispatcher
  "Two verbs, because the difference between them is the architecture.

  A single `publish` hides whether a message needs one handler or many. That
  is not a naming preference: the two have different cardinalities, different
  failure domains and different answers to \"what does it mean when nobody is
  listening?\"

      send-command!    exactly one destination
                       nobody listening is a routing error

      publish-event!   zero to many consumers
                       nobody listening is a legitimate state

  `consumers` is where that difference is enforced, and everything else --
  the relay, the delivery records, the dead letters -- is downstream of what
  it returns."
  (:require [lab29.platform.contract :as contract]
            [lab29.platform.message :as message]))

(defn dispatcher
  "`contracts` are the modules' declared contracts; `handlers` maps a module
  keyword to the function that receives its messages."
  [contracts handlers]
  {:routes   (contract/routes contracts)
   :handlers handlers})

(defn consumers
  "Who should receive this message.

  For a command: exactly one, and no destination is a failure rather than a
  quiet nothing. For an event: whoever subscribed, which may legitimately be
  nobody -- the producer does not know or care who cares."
  [{:keys [routes]} msg]
  (let [msg-type (message/message-type msg)]
    (if (message/command? msg)
      (if-let [destination (get-in routes [:commands msg-type])]
        #{destination}
        (throw (ex-info "No module handles this command"
                        {:reason :no-destination :command msg-type})))
      (get-in routes [:events msg-type] #{}))))

(defn deliver!
  "Hand one message to one named consumer."
  [{:keys [handlers]} consumer delivery]
  (if-let [handler (get handlers consumer)]
    (handler delivery)
    (throw (ex-info "No handler is wired for this consumer"
                    {:reason :unwired-consumer :consumer consumer}))))

;; ---------------------------------------------------------------------------
;; The synchronous path
;;
;; Cross-module state changes go through the outbox, so these are not how a
;; module normally talks to another one. They exist because the semantics
;; should be expressible directly -- a test asserting that a command has one
;; destination should not have to drain a queue to find out.
;; ---------------------------------------------------------------------------

(defn send-command!
  [dispatcher command]
  (let [[destination] (vec (consumers dispatcher command))]
    {:consumer destination
     :result   (deliver! dispatcher destination command)}))

(defn publish-event!
  [dispatcher event]
  (mapv (fn [consumer]
          {:consumer consumer :result (deliver! dispatcher consumer event)})
        (sort (consumers dispatcher event))))
