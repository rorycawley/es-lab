(ns lab29.platform.relay
  "Draining an outbox, one delivery at a time.

  Lab 28's relay drained *messages*, which is why its fan-out shared a failure
  domain. This one drains deliveries: expand a message into one record per
  consumer, work each independently, and mark the message published only when
  every consumer is finished with it -- delivered, or given up on.

  Three passes per message, then that consumer's delivery is dead-lettered and
  the others carry on. The count is not a retry budget: adapters already retry
  inside a single attempt, with backoff and a breaker. This is how many
  separate relay passes a consumer gets before we conclude the problem is the
  pairing rather than the moment."
  (:require [lab29.platform.delivery :as delivery]
            [lab29.platform.dispatcher :as dispatcher]
            [lab29.platform.message :as message]
            [lab29.platform.outbox :as outbox]
            [lab29.platform.telemetry :as telemetry]))

(def attempts-before-death 3)

(defn- span-name [msg consumer]
  (keyword (namespace (message/message-type msg))
           (str (if (message/command? msg) "send-" "publish-")
                (name (message/message-type msg))
                "-to-" (name consumer))))

(defn- attempt!
  [dispatcher datasource schema row {:keys [consumer attempts]}]
  (let [msg        (:message row)
        message-id (:message-id row)
        headers    (atom nil)]
    (try
      (telemetry/observe
       {:name       (span-name msg consumer)
        :kind       (if (message/command? msg) :client :producer)
        :parent     (when (:traceparent row) {"traceparent" (:traceparent row)})
        :attributes {:message-id message-id
                     :consumer   consumer
                     :attempts   attempts}}
       (fn []
         (let [trace-headers (telemetry/trace-headers)]
           (reset! headers trace-headers)
           (dispatcher/deliver! dispatcher consumer
                                {:headers trace-headers :message msg}))))
      (delivery/delivered! datasource schema message-id consumer)
      [:delivered {:message-id message-id
                   :consumer consumer
                   :headers @headers
                   :message msg}]
      (catch Throwable t
        (let [attempted (inc (or attempts 0))
              reason    (or (:reason (ex-data t)) :delivery-failed)
              detail    (str (name reason) ": " (ex-message t))]
          (if (< attempted attempts-before-death)
            (do (delivery/failed! datasource schema message-id consumer detail)
                [:failed {:message-id message-id :consumer consumer
                          :attempts attempted :because detail}])
            (do (delivery/dead-letter! datasource schema row consumer attempted detail)
                [:dead-lettered {:message-id message-id :consumer consumer
                                 :attempts attempted :because detail}])))))))

(defn drain!
  "Publish everything this module owes, and give up on what it cannot.

  Returns `{:delivered [...] :failed [...] :dead-lettered [...]}`. A failure
  is caught rather than propagated, so one consumer that will not accept a
  message stops neither the other consumers of that message nor the messages
  behind it."
  [dispatcher datasource schema]
  (reduce
   (fn [summary row]
     (let [message-id (:message-id row)
           msg        (:message row)]
       (delivery/expand! datasource schema message-id
                         (dispatcher/consumers dispatcher msg))
       (let [summary (reduce (fn [acc pending]
                               (let [[outcome detail]
                                     (attempt! dispatcher datasource schema row pending)]
                                 (update acc outcome conj detail)))
                             summary
                             (delivery/pending datasource schema message-id))]
         (outbox/settle! datasource schema message-id)
         summary)))
   {:delivered [] :failed [] :dead-lettered []}
   (outbox/unpublished datasource schema)))
