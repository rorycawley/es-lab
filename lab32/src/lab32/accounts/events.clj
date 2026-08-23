(ns lab32.accounts.events
  "Translating a domain event into something another module may hear.

  Lab 3's distinction, in one function. A domain event is Accounts' own record
  of what happened, shaped for folding into an aggregate. An integration event
  is a message in transit, shaped for a stranger -- which is why its contents
  are called a payload here and `:data` in the stream.

  Two domain events collapse into one integration event, and that is the
  interesting direction of the translation. Compliance does not care whether
  money arrived or left, only that a movement of a certain size occurred; so
  `money-deposited` and `money-withdrawn` both become
  `:accounts/transaction-recorded` with a direction. Publishing Accounts' own
  two event types instead would make Compliance's handler a `case` over
  Accounts' internal vocabulary, and every future event type Accounts invents
  would be a breaking change to a module that never wanted to know."
  (:require [lab32.money :as money]))

(def ^:private directions
  {:accounts/money-deposited "credit"
   :accounts/money-withdrawn "debit"})

(defn ->integration-event
  "The message to enqueue for one recorded domain event, or nil if this fact
  stays inside the module.

  Returning nil rather than throwing is the point: most domain events are
  nobody else's business, and that has to be the easy, quiet default. The
  caller uses `keep`."
  [event]
  (when-let [direction (directions (:event/type event))]
    {:event-id      (:event/id event)
     :source-module :accounts
     :event-type    :accounts/transaction-recorded

     ;; The aggregate id, which is what makes Phase 3's ordering guarantee
     ;; mean something useful. Two movements on one account share a partition
     ;; and are delivered in order; movements on different accounts do not
     ;; share one and are free to go in parallel. Ordering everything would be
     ;; a global lock wearing a queue's clothes.
     :partition-key (:aggregate/id event)

     :payload       {:account-id  (str (:aggregate/id event))
                     :amount      (money/of (get-in event [:data :amount]))
                     :direction   direction
                     ;; An ISO string, because JSON has no instant type and
                     ;; `clojure.data.json` will not serialise a Timestamp.
                     :occurred-at (str (.toInstant ^java.sql.Timestamp
                                        (:event/occurred-at event)))}
     ;; The event's own id travels in the outbox column rather than the
     ;; payload, so the inbox's UNIQUE constraint has it without anyone having
     ;; to parse a document to find it.
     :metadata      (select-keys (:metadata event) [:correlation-id :command-id])}))
