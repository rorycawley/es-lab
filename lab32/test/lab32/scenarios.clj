(ns lab32.scenarios
  "The Phase 1 properties, as functions rather than as tests.

  Acceptance test 9 asks for the whole Phase 1 suite to be re-run with the
  NOTIFY trigger disabled, to prove the fast path is a pure optimisation. A
  `deftest` cannot be re-run under a different configuration, so the
  assertions that matter live here and `fast_path_test.clj` runs all of them
  three ways:

      no trigger, no listener     Phase 1, as it was
      trigger, nobody listening   the doorbell rings in an empty house
      trigger and listener        Phase 2

  The middle configuration is the one worth having and is not in the build
  spec. It is Gotcha #3 made concrete: `NOTIFY` is at-most-once, and a signal
  nobody is subscribed to is discarded silently. A system in that state must
  behave exactly like one with no trigger at all -- and it is the state every
  deployment passes through, twice, every time it restarts."
  (:require [clojure.test :refer [is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.messaging.inbox :as inbox]
            [lab32.messaging.outbox :as outbox]
            [lab32.postgres :as postgres]
            [lab32.system :as system]
            [next.jdbc :as jdbc]))

(defn- open! [sys account]
  (accounts/open-account! (system/accounts-module sys)
                          {:account-id account :holder "Ada"}))

(defn- deposit! [sys account amount]
  (accounts/deposit! (system/accounts-module sys) {:account-id account :amount amount}))

;; ---------------------------------------------------------------------------

(defn a-deposit-reaches-the-read-model
  [sys]
  (testing "a deposit reaches the read model"
    (let [account (random-uuid)]
      (open! sys account)
      (deposit! sys account 12000)
      (system/settle! sys)
      (is (= 1 (count (postgres/inbox-rows))))
      (is (= 1 (count (postgres/flagged-rows))))
      (is (= "PROCESSED" (:status (first (postgres/outbox-rows))))))))

(defn a-movement-below-the-threshold-is-not-flagged
  [sys]
  (testing "a movement below the threshold is delivered and not flagged"
    (let [account (random-uuid)]
      (open! sys account)
      (deposit! sys account 9999)
      (system/settle! sys)
      (is (= 1 (count (postgres/inbox-rows))))
      (is (zero? (count (postgres/flagged-rows)))))))

(defn a-failure-after-the-outbox-insert-leaves-nothing-behind
  [sys]
  (testing "a failure after the outbox insert leaves nothing behind"
    (let [account (random-uuid)
          pool    (system/pool-for (:datasources sys) :accounts)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (jdbc/with-transaction [tx pool]
                     (outbox/enqueue! tx {:event-id      (random-uuid)
                                          :source-module :accounts
                                          :event-type    :accounts/transaction-recorded
                                          :partition-key account
                                          :payload       {:account-id (str account)}})
                     (throw (ex-info "died here" {:reason :deliberate})))))
      (is (zero? (count (postgres/outbox-rows)))))))

(defn a-refused-command-writes-nothing
  [sys]
  (testing "a refused command writes nothing"
    (let [account (random-uuid)]
      (open! sys account)
      (deposit! sys account 100)
      (is (thrown? clojure.lang.ExceptionInfo
                   (accounts/withdraw! (system/accounts-module sys)
                                       {:account-id account :amount 101})))
      (is (= 2 (count (postgres/event-rows))))
      (is (= 1 (count (postgres/outbox-rows)))))))

(defn a-dispatch-that-dies-still-delivers-exactly-once
  [sys]
  ;; Note what this does *not* assert: that the outbox row is PENDING straight
  ;; after the rollback. That is true of the transaction and it is not true of
  ;; the system, because in the listener-on configuration a live dispatcher may
  ;; legitimately have claimed and delivered the message already -- which is
  ;; the fast path doing its job, not a failure.
  ;;
  ;; `dispatch_test.clj` makes the strict claim, in the configuration where it
  ;; is deterministic. What survives every configuration is the property that
  ;; actually matters to a consumer: exactly one delivery, never zero.
  (testing "a dispatch that dies before commit still delivers exactly once"
    (let [account (random-uuid)
          pool    (system/pool-for (:datasources sys) :messaging)]
      (open! sys account)
      (deposit! sys account 12000)
      (try
        (jdbc/with-transaction [tx pool]
          (when-let [message (first (outbox/claim! tx 50))]
            (inbox/insert! tx "compliance" message)
            (throw (ex-info "died here" {:reason :deliberate}))))
        (catch clojure.lang.ExceptionInfo _ nil))
      (system/settle! sys)
      (is (= 1 (count (postgres/inbox-rows))))
      (is (= 1 (count (distinct (map :event-id (postgres/inbox-rows))))))
      (is (= 1 (count (postgres/flagged-rows)))))))

(defn a-redelivered-message-does-not-project-twice
  [sys]
  (testing "a redelivered message does not project twice"
    (let [account (random-uuid)]
      (open! sys account)
      (deposit! sys account 25000)
      (system/settle! sys)
      (dotimes [_ 3]
        (postgres/query "UPDATE messaging.outbox
                            SET status = 'PENDING', processed_at = NULL")
        (system/settle! sys))
      (is (= 1 (count (postgres/inbox-rows))))
      (is (= 1 (count (postgres/flagged-rows)))))))

(defn concurrent-deposits-produce-a-contiguous-stream
  [sys]
  (testing "concurrent deposits produce a contiguous stream"
    (let [account (random-uuid)
          module  (system/accounts-module sys)]
      (open! sys account)
      (run! deref (mapv (fn [_] (future (dotimes [_ 10]
                                          (accounts/deposit! module
                                                             {:account-id account
                                                              :amount 10}))))
                        (range 4)))
      (let [versions (map :version (postgres/event-rows))]
        (is (= (range 1 42) (sort versions)))
        (is (= 41 (count (distinct versions))))))))

(def every-scenario
  "Everything Phase 1 established, in one vector so it can be replayed under a
  different configuration."
  [a-deposit-reaches-the-read-model
   a-movement-below-the-threshold-is-not-flagged
   a-failure-after-the-outbox-insert-leaves-nothing-behind
   a-refused-command-writes-nothing
   a-dispatch-that-dies-still-delivers-exactly-once
   a-redelivered-message-does-not-project-twice
   concurrent-deposits-produce-a-contiguous-stream])
