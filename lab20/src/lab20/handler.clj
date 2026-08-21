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

(defn announce
  "Which outgoing messages a fact produces (lab 12's contract, recapped).

  Zero for most facts; this module tells other modules only about depletion.

  Note the payload key: `:fact-id`, not `:event/id`. JSON has no namespaces,
  so `:event/id` serialises to `\"id\"` and comes back meaning something else
  — the same lossiness lab 19 found in *values*, now in keys. A wire contract
  should not be carrying Clojure namespaces anyway: other modules read it, and
  they may not be Clojure at all."
  [event]
  (when (= :stock-depleted (:event/type event))
    [{:message-type :flavour-unavailable
      :recipient    :customer-app
      :payload      {:fact-id  (:event/id event)
                     :truck-id (:stream/id event)
                     :flavour  (get-in event [:data :flavour])}}
     {:message-type :restock-required
      :recipient    :purchasing
      :payload      {:fact-id  (:event/id event)
                     :truck-id (:stream/id event)
                     :flavour  (get-in event [:data :flavour])}}]))

(defn handle!
  "Read, fold, decide, append — and enqueue, and record. One transaction.

  Returns `:already-handled` if the ledger has seen this command before."
  [ds stream-id gen-id now command]
  (if (ledger/recorded? ds (:command/id command))
    :already-handled
    (jdbc/with-transaction [tx ds]
      (let [history (store/stream tx stream-id)
            version (store/current-version tx stream-id)
            state   (truck/replay history)
            decided (truck/decide command state)
            events  (store/append tx stream-id version gen-id now command decided)]
        (ledger/record! tx command (count events))
        (doseq [event events
                message (announce event)]
          (outbox/enqueue! tx (assoc message :message-id (gen-id))))
        events))))
