(ns registry.decider.runner
  (:require [registry.uuid :as uuid]))

;; =============================================================================
;; Generic Decider runner
;; Works for any Decider. Knows nothing about business rules.
;; =============================================================================

(defprotocol EventStore
  (load-events  [this aggregate-id])
  (save-events! [this aggregate-id events]))

(defprotocol EventBus
  (publish-events! [this events]))

(defn enrich [command correlation-id]
  (assoc command
         :command/id             (uuid/v7)
         :command/correlation-id (or correlation-id (uuid/v7))))

(defn run-command!
  [decider event-store aggregate-id command correlation-id]
  (let [enriched-cmd (enrich command correlation-id)
        past-events  (load-events event-store aggregate-id)
        state        ((:fold decider) past-events)]
    (if ((:terminal? decider) state)
      {:error :aggregate-terminal :aggregate aggregate-id :state (:status state)}
      (let [result ((:decide decider) state enriched-cmd)]
        (if (:error result)
          result
          (try
            (save-events! event-store aggregate-id (:events result))
            {:ok true}
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)]
                (cond
                  (= :idempotent (:reason data))
                  {:ok true :idempotent true}

                  (= :business-rule-failed (:reason data))
                  {:error (:error data)}

                  :else
                  (throw e))))))))))

(defn create!
  [decider event-store command correlation-id]
  (let [aggregate-id (uuid/v7-str)
        result       (run-command! decider event-store aggregate-id command correlation-id)]
    (if (:ok result)
      (assoc result :aggregate-id aggregate-id)
      result)))

(defn execute!
  [decider event-store aggregate-id command correlation-id]
  (run-command! decider event-store aggregate-id command correlation-id))
