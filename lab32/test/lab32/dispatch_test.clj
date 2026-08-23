(ns lab32.dispatch-test
  "§6.4 — outbox and inbox in one transaction.

  Acceptance tests 3 and 4."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.fixture :as fixture]
            [lab32.messaging.inbox :as inbox]
            [lab32.messaging.outbox :as outbox]
            [lab32.system :as system]
            [next.jdbc :as jdbc]))

(defn- a-deposit!
  "Open an account and deposit, leaving exactly one message in the outbox."
  [sys amount]
  (let [account (random-uuid)]
    (accounts/open-account! (fixture/accounts sys) {:account-id account :holder "Ada"})
    (accounts/deposit! (fixture/accounts sys) {:account-id account :amount amount})
    account))

;; ---------------------------------------------------------------------------
;; Acceptance test 3 — crash mid-dispatch
;; ---------------------------------------------------------------------------

(deftest a-dispatch-that-dies-before-commit-leaves-the-message-pending-test
  (fixture/with-system
    (fn [sys]
      (a-deposit! sys 12000)
      (let [pool (system/pool-for (:datasources sys) :messaging)]

        (testing "the transaction claims, delivers, and then dies"
          (is (thrown? clojure.lang.ExceptionInfo
                       (jdbc/with-transaction [tx pool]
                         (let [[message] (outbox/claim! tx 50)]
                           (is (some? message) "something was claimed")
                           (inbox/insert! tx "compliance" message)
                           ;; The moment the whole design is about. The outbox
                           ;; row is marked PROCESSED and the inbox row exists,
                           ;; both inside this transaction, and neither has
                           ;; been committed.
                           (throw (ex-info "the process died here"
                                           {:reason :deliberate})))))))

        (testing "the claim rolled back with everything else"
          (is (= "PENDING" (:status (first (fixture/outbox-rows))))
              "a message marked processed but never delivered would be lost")
          (is (zero? (count (fixture/inbox-rows)))
              "a message delivered but never marked would be delivered twice"))

        (testing "redelivery produces exactly one inbox row"
          (system/settle! sys)
          (is (= 1 (count (fixture/inbox-rows))))
          (is (= "PROCESSED" (:status (first (fixture/outbox-rows)))))
          (is (= 1 (count (fixture/flagged-rows)))))))))

;; ---------------------------------------------------------------------------
;; Acceptance test 4 — idempotency
;; ---------------------------------------------------------------------------

(deftest a-redelivered-message-does-not-project-twice-test
  ;; The build spec asks for this by inserting the same `event_id` into the
  ;; outbox twice by hand. That is not possible: `messaging.outbox` declares
  ;; `event_id UUID NOT NULL UNIQUE`, so the second insert is rejected by the
  ;; producer's own table rather than reaching the consumer at all.
  ;;
  ;; Which is worth knowing, but it is not the property under test. The
  ;; property is that the *consumer* absorbs a repeat, and the way a repeat
  ;; actually happens is redelivery -- a rolled-back dispatch, a reconciler
  ;; resending after a lost NOTIFY, a crash between commit and acknowledgement.
  ;; So this resets the outbox row to PENDING, which is exactly what a
  ;; rollback does, and dispatches it again.
  (fixture/with-system
    (fn [sys]
      (a-deposit! sys 25000)
      (system/settle! sys)
      (is (= 1 (count (fixture/inbox-rows))))
      (is (= 1 (count (fixture/flagged-rows))))

      (testing "the same message, sent again"
        (fixture/query "UPDATE messaging.outbox SET status = 'PENDING', processed_at = NULL")
        (system/settle! sys)

        (is (= 1 (count (fixture/inbox-rows)))
            "the inbox unique constraint absorbed the redelivery")
        (is (= 1 (count (fixture/flagged-rows)))
            "and the read model has one row, not two"))

      (testing "and again, five more times"
        (dotimes [_ 5]
          (fixture/query "UPDATE messaging.outbox SET status = 'PENDING', processed_at = NULL")
          (system/settle! sys))
        (is (= 1 (count (fixture/inbox-rows))))
        (is (= 1 (count (fixture/flagged-rows))))))))

(deftest inserting-the-same-event-into-an-inbox-twice-is-a-no-op-test
  ;; The same guarantee, one layer down, so a failure points at the constraint
  ;; rather than at the machinery above it.
  (fixture/with-system
    (fn [sys]
      (a-deposit! sys 12000)
      (let [pool (system/pool-for (:datasources sys) :messaging)]
        (jdbc/with-transaction [tx pool]
          (let [[message] (outbox/claim! tx 50)]
            (is (true? (inbox/insert! tx "compliance" message)) "first delivery is new")
            (is (false? (inbox/insert! tx "compliance" message)) "second is discarded")))
        (is (= 1 (count (fixture/inbox-rows))))))))

(deftest projecting-the-same-event-twice-is-a-no-op-test
  ;; And one layer further down still. The inbox stops a duplicate *delivery*;
  ;; this is what stops a duplicate *projection*, which is a different event --
  ;; a replay, a redriven dead letter, a handler that ran twice.
  (fixture/with-system
    (fn [sys]
      (a-deposit! sys 30000)
      (system/settle! sys)
      (let [before (fixture/flagged-rows)]
        (fixture/query "UPDATE compliance.inbox SET status = 'PENDING'")
        (system/settle! sys)
        (is (= before (fixture/flagged-rows))
            "re-running the handler changed nothing")))))

;; ---------------------------------------------------------------------------
;; Fan-out and the empty case
;; ---------------------------------------------------------------------------

(deftest a-message-nobody-subscribes-to-is-still-settled-test
  ;; Zero consumers is a legitimate answer for an event, and the message must
  ;; not sit PENDING forever waiting for a subscriber that does not exist.
  (fixture/with-system
    (fn [sys]
      (let [pool (system/pool-for (:datasources sys) :accounts)]
        (jdbc/with-transaction [tx pool]
          (outbox/enqueue! tx {:event-id      (random-uuid)
                               :source-module :accounts
                               :event-type    :accounts/nobody-is-listening
                               :partition-key (random-uuid)
                               :payload       {:whatever true}}))
        (system/settle! sys)
        (is (= "PROCESSED" (:status (first (fixture/outbox-rows)))))
        (is (zero? (count (fixture/inbox-rows))))))))

(deftest draining-an-empty-outbox-does-nothing-test
  (fixture/with-system
    (fn [sys]
      (is (zero? (system/dispatch! sys)))
      (is (= {:compliance {:handled 0 :failed 0}} (system/work-inboxes! sys))))))
