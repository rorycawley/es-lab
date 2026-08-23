(ns lab32.write-path-test
  "§6.1 — one transaction, no dual write.

  Acceptance tests 1, 2 and 5."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.accounts.repository :as repository]
            [lab32.compliance.api :as compliance]
            [lab32.fixture :as fixture]
            [lab32.messaging.outbox :as outbox]
            [lab32.money :as money]
            [lab32.system :as system]
            [next.jdbc :as jdbc]))

(defn- open! [system account holder]
  (accounts/open-account! (fixture/accounts system)
                          {:account-id account :holder holder}))

;; ---------------------------------------------------------------------------
;; Acceptance test 1 — happy path
;; ---------------------------------------------------------------------------

(deftest a-deposit-reaches-the-read-model-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account "Ada")
        (accounts/deposit! (fixture/accounts sys) {:account-id account :amount 12000})

        (testing "the state change and the message committed together"
          (is (= 2 (count (fixture/event-rows))))
          (is (= 1 (count (fixture/outbox-rows))) "only the movement is published"))

        (testing "nothing has moved before anything drains it"
          (is (zero? (count (fixture/inbox-rows))))
          (is (zero? (count (fixture/flagged-rows)))))

        (system/settle! sys)

        (testing "the event arrives in the consumer's inbox"
          (let [[row] (fixture/inbox-rows)]
            (is (= "accounts/transaction-recorded" (:event-type row)))
            (is (= (str account) (:partition-key row)))))

        (testing "and above the threshold, in the read model"
          (let [[flagged] (compliance/flagged-transactions (fixture/compliance sys))]
            (is (= account (:account-id flagged)))
            (is (= "credit" (:direction flagged)))
            (is (zero? (compare (money/of 12000) (:amount flagged))))))))))

(deftest a-movement-below-the-threshold-is-delivered-and-not-flagged-test
  ;; A consumer deciding an event is uninteresting has still consumed it. The
  ;; inbox row must be PROCESSED, because "I looked and there was nothing to
  ;; do" and "I have not looked yet" are different states.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account "Ada")
        (accounts/deposit! (fixture/accounts sys) {:account-id account :amount 9999})
        (system/settle! sys)
        (is (= 1 (count (fixture/inbox-rows))))
        (is (= "PROCESSED" (:status (first (fixture/inbox-rows)))))
        (is (zero? (count (fixture/flagged-rows))))))))

(deftest exactly-ten-thousand-is-not-over-the-threshold-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account "Ada")
        (accounts/deposit! (fixture/accounts sys) {:account-id account :amount 10000})
        (system/settle! sys)
        (is (zero? (count (fixture/flagged-rows))) "the rule is strictly greater than")))))

(deftest an-internal-event-is-not-published-test
  ;; `:accounts/account-opened` is a fact Accounts records about itself. Lab 3's
  ;; distinction, asserted: the domain event exists and no message carries it.
  (fixture/with-system
    (fn [sys]
      (open! sys (random-uuid) "Ada")
      (is (= 1 (count (fixture/event-rows))))
      (is (zero? (count (fixture/outbox-rows)))))))

;; ---------------------------------------------------------------------------
;; Acceptance test 2 — rollback
;; ---------------------------------------------------------------------------

(deftest a-failure-after-the-outbox-insert-leaves-nothing-behind-test
  ;; The dual write, and its absence.
  ;;
  ;; Driven through the repository and outbox directly rather than through the
  ;; API, because the thing under test is the transaction boundary itself: the
  ;; event is appended, the message is enqueued, and *then* it fails. If those
  ;; two writes were in separate transactions, the first would survive and the
  ;; system would hold a state change nobody was told about.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)
            pool    (system/pool-for (:datasources sys) :accounts)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (jdbc/with-transaction [tx pool]
                       (repository/append! tx account 0
                                           [{:event/type :accounts/money-deposited
                                             :data {:account-id account
                                                    :amount (money/of 500)}}]
                                           {:new-id random-uuid :metadata {}})
                       (outbox/enqueue! tx {:event-id      (random-uuid)
                                            :source-module :accounts
                                            :event-type    :accounts/transaction-recorded
                                            :partition-key account
                                            :payload       {:account-id (str account)}})
                       (throw (ex-info "the write path fell over here"
                                       {:reason :deliberate})))))

        (testing "neither half survived"
          (is (zero? (count (fixture/event-rows))))
          (is (zero? (count (fixture/outbox-rows)))))))))

(deftest a-refused-command-writes-nothing-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account "Ada")
        (accounts/deposit! (fixture/accounts sys) {:account-id account :amount 100})
        (is (thrown? clojure.lang.ExceptionInfo
                     (accounts/withdraw! (fixture/accounts sys)
                                         {:account-id account :amount 101})))
        (testing "the refusal left no event and no message"
          (is (= 2 (count (fixture/event-rows))))
          (is (= 1 (count (fixture/outbox-rows)))))))))

;; ---------------------------------------------------------------------------
;; Acceptance test 5 — concurrency
;; ---------------------------------------------------------------------------

(deftest a-hundred-concurrent-deposits-produce-a-contiguous-stream-test
  (fixture/with-system
    (fn [sys]
      (let [account  (random-uuid)
            module   (fixture/accounts sys)
            per      25
            threads  4]
        (open! sys account "Ada")
        (let [workers (mapv (fn [_]
                              (future
                                (dotimes [_ per]
                                  (accounts/deposit! module {:account-id account
                                                             :amount 10}))))
                            (range threads))]
          (run! deref workers))

        (testing "every deposit was recorded exactly once"
          (let [events (repository/history (system/pool-for (:datasources sys) :accounts)
                                           account)]
            (is (= (inc (* per threads)) (count events)))

            (testing "and the versions are 1..N with no gaps and no duplicates"
              (is (= (range 1 (inc (count events)))
                     (map :aggregate/version events))))))

        (testing "the folded balance is right"
          (is (zero? (compare (money/of (* per threads 10))
                              (:balance (accounts/balance module account))))))

        (testing "and one message was published per movement"
          (is (= (* per threads) (count (fixture/outbox-rows)))))))))

(deftest concurrent-withdrawals-cannot-overdraw-test
  ;; The invariant under contention. Eight threads race to withdraw more than
  ;; the account holds; the version constraint forces each to re-decide against
  ;; the real balance, so the refusals are real refusals rather than a race
  ;; nobody noticed.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)
            module  (fixture/accounts sys)]
        (open! sys account "Ada")
        (accounts/deposit! module {:account-id account :amount 100})
        (let [attempts (mapv (fn [_]
                               (future
                                 (try
                                   (accounts/withdraw! module {:account-id account
                                                               :amount 20})
                                   :ok
                                   (catch clojure.lang.ExceptionInfo e
                                     (:reason (ex-data e))))))
                             (range 8))
              outcomes (frequencies (map deref attempts))]
          (is (= 5 (:ok outcomes)) "exactly five withdrawals of 20 fit in 100")
          (is (= 3 (:insufficient-funds outcomes)))
          (is (zero? (compare 0M (:balance (accounts/balance module account))))))))))

;; ---------------------------------------------------------------------------
;; Gotcha #10 — money
;; ---------------------------------------------------------------------------

(deftest money-survives-the-round-trip-as-a-big-decimal-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account "Ada")
        (accounts/deposit! (fixture/accounts sys) {:account-id account :amount 12000.50M})
        (system/settle! sys)

        (testing "in the event stream"
          (let [event (last (accounts/history (fixture/accounts sys) account))]
            (is (money/money? (get-in event [:data :amount]))
                "an amount read back out of JSONB is a scaled BigDecimal")))

        (testing "in the outbox and the inbox"
          ;; `:payload` arrives already decoded: `db/json.clj` extends
          ;; `ReadableColumn`, so every JSONB column in the lab comes back as
          ;; Clojure data without anyone remembering to unwrap it.
          (doseq [row [(first (fixture/outbox-rows)) (first (fixture/inbox-rows))]]
            (is (money/money? (:amount (:payload row)))))

          (testing "and in the read model, which the column type enforces too"
            (is (money/money? (:amount (first (fixture/flagged-rows)))))))))))
