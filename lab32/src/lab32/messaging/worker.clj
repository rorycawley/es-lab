(ns lab32.messaging.worker
  "Draining one module's inbox into that module's projections.

  One worker per consuming module, each connecting as that module's own
  Postgres role. The dispatcher put the message here; what happens to it now is
  entirely the module's business, and the transport has no rights to watch.

  The unit of work is one message in one transaction, and this is lab 20's
  `handle-once!` with the claim folded in. The claim, the handler's writes and
  the completion all commit together, so there is no state in which the
  projection happened and the inbox has forgotten -- or the reverse."
  (:require [clojure.tools.logging :as log]
            [lab32.messaging.failure :as failure]
            [lab32.messaging.inbox :as inbox]
            [next.jdbc :as jdbc]))

(defn- handle-one!
  "Claim one pending message and run the handler against it, in one
  transaction.

  Returns `:handled`, `:failed` or `:empty`. The `claimed` atom is here for the
  reason `dispatcher/deliver-one!` explains: after a rollback the database no
  longer knows which message we were working on, and the attempt has to be
  counted against something."
  [{:keys [datasource schema handler retry]}]
  (let [claimed (atom nil)]
    (try
      (jdbc/with-transaction [tx datasource]
        (if-let [message (first (inbox/claim! tx schema 1))]
          (do (reset! claimed message)
              (handler tx message)
              :handled)
          :empty))
      (catch Throwable t
        (if-let [message @claimed]
          (do (log/warn t "handler failed for" (:event-id message))
              (inbox/record-failure! datasource schema (:seq message) (failure/describe t) retry)
              :failed)
          (do (log/warn t "could not claim from the inbox")
              :empty))))))

(def ^:private contended-passes
  "How many empty claims in a row mean the inbox is actually empty, rather
  than that another worker got there first. See `drain!`."
  3)

(defn- handle-partition!
  "Phase 3. Claim one partition and handle all of it, in order, in one
  transaction.

  Read the failure branch carefully, because it is where ordering costs
  something and the cost is not a bug.

  If the third of five messages for an account throws, the transaction rolls
  back -- and that undoes the first two as well, even though they succeeded.
  They return to PENDING and will be handled again. This is not sloppiness; it
  is what ordering *means*. Committing the first two and dead-lettering the
  third would leave messages four and five free to be applied to an account
  that never saw the third, which is precisely the outcome the partition lock
  exists to prevent. In-order delivery and per-message failure isolation are
  not both available, and a system claiming both has quietly stopped providing
  one of them.

  What does survive is isolation *between* partitions: a stuck account blocks
  itself and nothing else. And re-handling the first two costs nothing, because
  the projection is keyed on `event_id` -- which is why lab 9's rule about read
  models being rebuildable keeps earning its place."
  [{:keys [datasource schema handler retry]}]
  (let [failed-on (atom nil)]
    (try
      (jdbc/with-transaction [tx datasource]
        (let [messages (inbox/claim-partition! tx schema)]
          (doseq [message messages]
            (reset! failed-on message)
            (handler tx message))
          (reset! failed-on nil)
          (if (seq messages) [:handled (count messages)] [:empty 0])))
      (catch Throwable t
        (if-let [message @failed-on]
          (do (log/warn t "handler failed for" (:event-id message))
              (inbox/record-failure! datasource schema (:seq message)
                                     (failure/describe t) retry)
              [:failed 1])
          (do (log/warn t "could not claim from the inbox")
              [:empty 0]))))))

(defn drain!
  "Work up to `batch-size` messages. Returns `{:handled n :failed n}`.

  A failed message goes back to PENDING with `next_attempt_at` in the future,
  so the next pass steps over it rather than retrying it immediately and
  spending the whole batch on one broken thing. That is what makes acceptance
  test 6's poison message harmless to the messages behind it: it is not
  quarantined in another table, it is simply not due yet."
  [{:keys [batch-size claim-strategy] :as worker}]
  (if (= :partition claim-strategy)
    ;; An empty claim means one of two things and the query cannot tell them
    ;; apart: the inbox is empty, or another worker holds the partition this
    ;; one picked. Treating the first empty pass as end-of-queue would make a
    ;; contended worker give up while there is work it could do, so a few
    ;; empties are tolerated before concluding there is nothing left. The cost
    ;; is a couple of cheap queries at the end of a drain.
    (loop [remaining batch-size handled 0 failed 0 empties 0]
      (if (or (not (pos? remaining)) (>= empties contended-passes))
        {:handled handled :failed failed}
        (let [[outcome n] (handle-partition! worker)]
          (case outcome
            :handled (recur (- remaining n) (+ handled n) failed 0)
            :failed  (recur (dec remaining) handled (inc failed) 0)
            :empty   (recur remaining handled failed (inc empties))))))
    (loop [remaining batch-size handled 0 failed 0]
      (if-not (pos? remaining)
        {:handled handled :failed failed}
        (case (handle-one! worker)
          :handled (recur (dec remaining) (inc handled) failed)
          :failed  (recur (dec remaining) handled (inc failed))
          :empty   {:handled handled :failed failed})))))

(defn worker
  [datasource {:keys [module schema handler batch-size max-attempts backoff-seconds
                      claim-strategy]
               :or   {batch-size 50 max-attempts 20 backoff-seconds 1
                      claim-strategy :partition}}]
  {:datasource     datasource
   :module         module
   :schema         schema
   :handler        handler
   :batch-size     batch-size
   :claim-strategy claim-strategy
   :retry          {:max-attempts max-attempts :backoff-seconds backoff-seconds}})
