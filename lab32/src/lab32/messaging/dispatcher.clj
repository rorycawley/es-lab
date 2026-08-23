(ns lab32.messaging.dispatcher
  "Outbox to inbox, in one transaction, and the one function everything else
  calls.

  This is the design property the whole lab turns on. The reconciler (§6.6) and
  the NOTIFY listener (§6.5) are both *triggers* for `drain!` and neither
  contains any delivery logic of its own. The fast path and the slow path are
  therefore not two implementations that must be kept in agreement -- they are
  two ways of ringing the same bell. Acceptance test 9 removes the fast path
  entirely and asserts nothing else changes, which is only possible because
  there is nothing else to change.

  Because the outbox and the inboxes live in one database, claiming and
  delivering share a transaction. There is no window in which a message is
  marked processed but not delivered, and none in which it is delivered but not
  marked. Do not split this into two transactions to make it \"more modular\";
  the atomicity is the feature, and it is the one thing a broker on the far
  side of a network cannot give you."
  (:require [clojure.tools.logging :as log]
            [lab32.messaging.inbox :as inbox]
            [lab32.messaging.outbox :as outbox]
            [lab32.messaging.failure :as failure]
            [lab32.messaging.router :as router]
            [next.jdbc :as jdbc])
  (:import (java.util.concurrent Semaphore)))

(defn- fan-out!
  "Insert one claimed message into every subscribed module's inbox."
  [tx router message]
  (doseq [module (router/targets router (:event-type message))]
    (inbox/insert! tx (router/schema-of router module) message)))

(defn- deliver-batch!
  "Claim a batch and deliver all of it in one transaction.

  Two ways to claim, and the difference is the whole of Phase 3.

  `:skip-locked` takes individual rows and makes **no ordering promise**
  (Gotcha #8). It is here because it is what Phase 1 shipped, and because
  `ordering_test.clj` runs the same workload through both -- a claim that the
  partition strategy bought something is worth more when you can watch the
  other one fail to.

  `:partition` takes whole accounts under an advisory lock, and everything for
  one account is then delivered by one dispatcher in `seq` order.

  Note that the *delivery* is identical either way. The strategy chooses which
  rows arrive here; what happens to them does not change, which is why turning
  ordering on cannot alter any of the correctness properties below."
  [{:keys [datasource router batch-size claim-strategy] :as dispatcher}]
  (jdbc/with-transaction [tx datasource]
    (let [messages (case claim-strategy
                     :partition   (outbox/claim-partitions! tx dispatcher)
                     :skip-locked (outbox/claim! tx batch-size)
                     (throw (ex-info "Unknown claim strategy"
                                     {:reason :unknown-claim-strategy
                                      :claim-strategy claim-strategy})))]
      ;; `run!` and not `pmap`. The ordering the claim just established would
      ;; be thrown away by delivering in parallel, and these are local inserts
      ;; in an open transaction -- there is nothing to wait for.
      (run! #(fan-out! tx router %) messages)
      (count messages))))

(defn- deliver-one!
  "Claim and deliver exactly one message, isolated in its own transaction.

  Note the `claimed` atom. When the transaction rolls back, the claim rolls
  back with it and the row returns to PENDING -- so afterwards the database can
  no longer tell us which message we were working on. Capturing it in a local
  before the failure is what lets `record-failure!` find the row again and
  count the attempt against it. Without that, a message that always fails would
  sit at `attempts = 0` forever and never reach the dead-letter state."
  [{:keys [datasource router retry]}]
  (let [claimed (atom nil)]
    (try
      (jdbc/with-transaction [tx datasource]
        (if-let [message (first (outbox/claim! tx 1))]
          (do (reset! claimed message)
              (fan-out! tx router message)
              :delivered)
          :empty))
      (catch Throwable t
        (if-let [message @claimed]
          (do (log/warn t "dispatch failed for" (:event-id message))
              (outbox/record-failure! datasource (:seq message) (failure/describe t) retry)
              :failed)
          (do (log/warn t "could not claim from the outbox")
              :empty))))))

(defn- isolate!
  "One message at a time, after a batch failed.

  A batch is all-or-nothing: one message that cannot be delivered rolls back
  every message beside it, and retrying the batch fails in exactly the same
  way, forever. Dropping to single messages costs a transaction each and buys
  the property acceptance test 6 asks for -- a message nobody can deliver stops
  being everybody else's problem."
  [{:keys [batch-size] :as dispatcher}]
  (loop [remaining batch-size delivered 0]
    (if (zero? remaining)
      delivered
      (case (deliver-one! dispatcher)
        :delivered (recur (dec remaining) (inc delivered))
        :failed    (recur (dec remaining) delivered)
        :empty     delivered))))

(defn- drain-once!
  [dispatcher]
  (try
    (deliver-batch! dispatcher)
    (catch Throwable t
      (log/warn t "batch dispatch failed; retrying one message at a time")
      (isolate! dispatcher))))

(defn drain!
  "Move everything the outbox owes into the inboxes that want it.

  Guarded so that concurrent callers within one JVM coalesce rather than
  queue. The listener and the reconciler both call this, and under load the
  listener may call it many times a second; without the guard, each call would
  open a transaction and contend on the same rows for no benefit.

  The `rerun` flag is the part that is easy to get wrong. A naive
  `tryAcquire`-or-return drops a kick that arrives while a drain is finishing,
  and the message that kick was about then waits for the reconciler -- an
  intermittent multi-second latency under exactly the load the fast path exists
  for. Recording that a kick was missed and looping once more closes it."
  [{:keys [^Semaphore semaphore rerun] :as dispatcher}]
  (if (.tryAcquire semaphore)
    (try
      (loop [total 0]
        (reset! rerun false)
        (let [moved (drain-once! dispatcher)]
          (if (or (pos? moved) @rerun)
            (recur (+ total moved))
            total)))
      (finally
        (.release semaphore)))
    (do (reset! rerun true)
        :coalesced)))

(defn dispatcher
  [datasource router {:keys [batch-size max-attempts backoff-seconds
                             claim-strategy partition-limit]
                      :or   {batch-size 50 max-attempts 20 backoff-seconds 1
                             claim-strategy :partition partition-limit 20}}]
  {:datasource      datasource
   :router          router
   :batch-size      batch-size
   :claim-strategy  claim-strategy
   :partition-limit partition-limit
   :retry           {:max-attempts max-attempts :backoff-seconds backoff-seconds}
   :semaphore       (Semaphore. 1)
   :rerun           (atom false)})
