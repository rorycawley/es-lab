(ns registry.messaging.outbox-publisher
  (:require [registry.messaging.rabbitmq-event-bus :as rmq]))

(defn- publish-one! [conn outbox-handle event]
  (try
    (rmq/publish-one! conn event)
    ((:mark-published! outbox-handle) (:event/id event))
    true
    (catch Exception e
      (println "Outbox publisher failed:" (:event/id event) (.getMessage e))
      false)))

(defn run-poll-cycle! [conn outbox-handle batch-size]
  (loop [events (seq ((:fetch-unpublished outbox-handle) batch-size))]
    (when-let [event (first events)]
      (when (publish-one! conn outbox-handle event)
        (recur (next events))))))
