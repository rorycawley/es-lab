(ns lab20.handler
  "One command, one transaction: the events, the ledger row, and the outgoing
  messages all commit together.

  That is the whole of the outbox pattern. Not 'write the event, then publish'
  — one write, containing everything the outside world will later learn."
  (:require [lab20.ledger :as ledger]
            [lab20.outbox :as outbox]
            [lab20.store :as store]
            [lab20.truck :as truck]
            [next.jdbc :as jdbc]))

(defn- identify [gen-id now command event]
  (assoc event
         :event/id (gen-id)
         :event/occurred-at now
         :metadata {:causation-id (:command/id command)
                    :correlation-id (:correlation-id command)}))

(defn announce
  "Which outgoing messages a fact produces (lab 12's contract, recapped).

  Zero for most facts; this module tells other modules only about depletion.

  Note the payload key: `:fact-id`, not `:event/id`. JSON has no namespaces,
  so `:event/id` serialises to `\"id\"` and comes back meaning something else
  — the same lossiness lab 19 found in *values*, now in keys. A wire contract
  should not be carrying Clojure namespaces anyway: other modules read it, and
  they may not be Clojure at all."
  [event]
  (case (:event/type event)
    :stock-depleted
    [{:message-type :flavour-unavailable
      :recipient    :customer-app
      :payload      {:fact-id  (str (:event/id event))
                     :truck-id (str (:stream/id event))
                     :flavour  (get-in event [:data :flavour])}}
     {:message-type :restock-required
      :recipient    :purchasing
      :payload      {:fact-id  (str (:event/id event))
                     :truck-id (str (:stream/id event))
                     :flavour  (get-in event [:data :flavour])}}]

    :truck-loaded []
    :flavour-sold []

    (throw (ex-info "Unknown event type"
                    {:event/type (:event/type event)}))))

(defn handle!
  "Read, fold, decide, append — and enqueue, and record. One transaction.

  Returns `:already-handled` if the ledger has seen this command before."
  [ds stream-id gen-id now command]
  (letfn [(already-handled []
            (when-let [entry (ledger/entry ds (:command/id command))]
              (ledger/assert-same-command! entry stream-id command)
              :already-handled))]
    (or (already-handled)
        (try
          (jdbc/with-transaction [tx ds]
            (let [history  (store/stream tx stream-id)
                  version  (or (:stream/version (peek history)) 0)
                  state    (truck/replay history)
                  decided  (truck/decide command state)
                  proposed (mapv #(identify gen-id now command %) decided)
                  events   (store/append-in-transaction!
                            tx stream-id version proposed)]
              (ledger/record! tx stream-id command (count events))
              (doseq [event events
                      message (announce event)]
                (outbox/enqueue! tx (assoc message :message-id (gen-id))))
              events))
          (catch Exception failure
            ;; A competing execution can commit while this transaction waits
            ;; on the stream head or ledger uniqueness constraint.
            (or (already-handled) (throw failure)))))))
