(ns lab32.compliance.api
  "Compliance's public module API.

  A consumer's public surface is smaller than a producer's and shaped
  differently: one handler, which the inbox worker calls, and the queries that
  make the read model worth maintaining. There is no `handle-integration-event`
  that anybody may call directly with a message they made up -- the handler is
  handed to the worker at construction and reaches the outside world only
  through the inbox."
  (:require [lab32.compliance.projections :as projections]))

(defn- handle!
  "Dispatch one delivered message.

  An unrecognised event type throws rather than being ignored. It cannot happen
  -- `router.clj` only routes what this module declared it consumes -- so if it
  does happen, something is wrong that a silent no-op would hide until the
  numbers were questioned. The message will retry, back off, and eventually
  land in the dead-letter state where somebody can see it."
  [tx {:keys [event-type] :as message}]
  (case event-type
    :accounts/transaction-recorded (projections/handle-transaction-recorded! tx message)
    (throw (ex-info "Compliance was sent an event it does not consume"
                    {:reason :unroutable-event :event-type event-type}))))

(defrecord Compliance [handler flagged clear])

(defn new-module
  [datasource]
  (->Compliance handle!
                (fn flagged
                  ([] (projections/flagged-transactions datasource))
                  ([account-id] (projections/flagged-transactions datasource account-id)))
                #(projections/clear! datasource)))

(defn handler
  "The function the inbox worker runs, in the worker's transaction."
  [compliance]
  (:handler compliance))

(defn flagged-transactions
  ([compliance] ((:flagged compliance)))
  ([compliance account-id] ((:flagged compliance) account-id)))

(defn clear!
  "Drop the read model so it can be rebuilt. Phase 4."
  [compliance]
  ((:clear compliance)))
