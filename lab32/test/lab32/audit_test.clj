(ns lab32.audit-test
  "Phase 4 — the three things a broker cannot do.

  Everything up to here has been about matching what a message broker gives
  you: at-least-once delivery, idempotency, ordering, low latency. This
  namespace is the other half of the argument, and it is the half Revolut
  actually gave as their reason for not using Kafka -- ad-hoc queries, queries
  by time, and consistency between the state change and the event.

  None of these are clever. That is the point: they are `SELECT` statements
  against a table that was never pruned, and they are unavailable at any price
  from a log with a retention window."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.compliance.api :as compliance]
            [lab32.fixture :as fixture]
            [lab32.money :as money]
            [lab32.postgres :as postgres]
            [lab32.system :as system]))

(defn- history!
  "An account with a known shape: opened, three deposits, one withdrawal."
  [sys]
  (let [account (random-uuid)
        module  (system/accounts-module sys)]
    (accounts/open-account! module {:account-id account :holder "Ada"})
    (accounts/deposit! module {:account-id account :amount 5000})
    (accounts/deposit! module {:account-id account :amount 25000})
    (accounts/deposit! module {:account-id account :amount 500})
    (accounts/withdraw! module {:account-id account :amount 12000})
    account))

;; ---------------------------------------------------------------------------
;; GET /audit/account/:id
;; ---------------------------------------------------------------------------

(deftest an-account-is-rebuilt-from-its-events-on-demand-test
  (fixture/with-system
    (fn [sys]
      (let [account (history! sys)
            events  (accounts/history (system/accounts-module sys) account)]

        (testing "the whole history, in order, with nothing thrown away"
          (is (= [:accounts/account-opened
                  :accounts/money-deposited
                  :accounts/money-deposited
                  :accounts/money-deposited
                  :accounts/money-withdrawn]
                 (mapv :event/type events)))
          (is (= [1 2 3 4 5] (mapv :aggregate/version events))))

        (testing "and the balance is derived from it, not stored"
          (is (zero? (compare (money/of 18500)
                              (:balance (accounts/balance
                                         (system/accounts-module sys) account))))))))))

;; ---------------------------------------------------------------------------
;; GET /audit/query
;; ---------------------------------------------------------------------------

(deftest the-whole-history-is-queryable-by-type-amount-and-time-test
  (fixture/with-system
    (fn [sys]
      (let [module (system/accounts-module sys)]
        (dotimes [_ 3] (history! sys))

        (testing "by event type"
          (is (= 9 (count (accounts/search module {:event-type "accounts/money-deposited"}))))
          (is (= 3 (count (accounts/search module {:event-type "accounts/money-withdrawn"})))))

        (testing "by a predicate inside the event's own data"
          ;; The GIN index on `data jsonb_path_ops` is what stops this being a
          ;; sequential scan over everything that ever happened.
          (let [big (accounts/search module {:event-type "accounts/money-deposited"
                                             :min-amount (money/of 10000)})]
            (is (= 3 (count big)))
            (is (every? #(>= (compare (get-in % [:data :amount]) (money/of 10000)) 0) big))))

        (testing "by time, which is the query a topic cannot answer at all"
          (let [past   (java.sql.Timestamp/from
                        (.minusSeconds (java.time.Instant/now) 3600))
                future (java.sql.Timestamp/from
                        (.plusSeconds (java.time.Instant/now) 3600))]
            (is (pos? (count (accounts/search module {:from past :until future}))))
            (is (zero? (count (accounts/search module {:from future}))))
            (is (zero? (count (accounts/search module {:until past})))))
          (is (= 15 (count (accounts/search module {:limit 100})))))

        (testing "and the two combine"
          (is (= 3 (count (accounts/search
                           module
                           {:event-type "accounts/money-deposited"
                            :min-amount (money/of 10000)
                            :until (java.sql.Timestamp/from
                                    (.plusSeconds (java.time.Instant/now) 3600))})))))))))

;; ---------------------------------------------------------------------------
;; POST /audit/replay/:module
;; ---------------------------------------------------------------------------

(deftest a-read-model-is-rebuilt-from-the-stream-and-comes-out-identical-test
  ;; Lab 9's rule, and the payoff for never pruning the stream. The projection
  ;; is dropped and rebuilt from events recorded before it existed, and the
  ;; result has to be the same row for row -- otherwise it was not a
  ;; projection, it was a second source of truth.
  (fixture/with-system
    (fn [sys]
      (dotimes [_ 3] (history! sys))
      (system/settle! sys)

      (let [by-id    #(vec (sort-by :event-id (map (fn [row] (dissoc row :flagged-at)) %)))
            original (compliance/flagged-transactions (fixture/compliance sys))]
        (is (= 6 (count original)) "three accounts, one large deposit and one large withdrawal each")

        (testing "the replay clears the read model and its inbox"
          ;; Twelve, not six: `republish!` re-derives every *integration
          ;; event* -- four movements per account -- and Compliance flags the
          ;; two that cross the threshold. The read model is a filter over
          ;; what it was sent, not a copy of it.
          (is (= 12 (system/replay! sys :compliance))
              "the messages are reconstructed from events, not recovered from a queue")
          (is (zero? (count (postgres/flagged-rows))))
          (is (zero? (count (postgres/inbox-rows)))
              "clearing the inbox too, or the unique constraint would discard the replay"))

        (testing "and rebuilds it from the event stream"
          (system/settle! sys)

          (let [rebuilt (compliance/flagged-transactions (fixture/compliance sys))]
            ;; Compared by event id rather than in listing order. The default
            ;; ordering is by `flagged_at`, and a rebuild stamps every row
            ;; within the same millisecond -- so the *order* legitimately
            ;; differs while the content must not.
            (is (= (by-id original) (by-id rebuilt))
                "identical, apart from when the rebuild happened")))))))

(deftest replay-works-after-the-queues-have-been-pruned-test
  ;; The point of the retention asymmetry, made as a test. Everything
  ;; transport-related is deleted -- the exact state a broker with a 24-hour
  ;; window is in the next day -- and the read model is still rebuildable,
  ;; because the facts were never in the transport to begin with.
  (fixture/with-system
    (fn [sys]
      (dotimes [_ 2] (history! sys))
      (system/settle! sys)
      (let [by-id    #(vec (sort-by :event-id (map (fn [row] (dissoc row :flagged-at)) %)))
            original (compliance/flagged-transactions (fixture/compliance sys))]

        (postgres/query "DELETE FROM messaging.outbox")
        (is (zero? (count (postgres/outbox-rows))))

        (system/replay! sys :compliance)
        (is (zero? (count (postgres/flagged-rows))))
        (is (= 8 (count (postgres/outbox-rows)))
            "with nothing to resurrect, the producer simply enqueues fresh rows")
        (system/settle! sys)

        (is (= (by-id original)
               (by-id (compliance/flagged-transactions (fixture/compliance sys)))))))))

(deftest the-event-stream-is-never-touched-by-a-replay-test
  ;; A rebuild reads history and must not write any. If replaying could append
  ;; to the stream, running it twice would change the answers -- and the one
  ;; thing an audit log may never do is depend on how often somebody looked
  ;; at it.
  (fixture/with-system
    (fn [sys]
      (dotimes [_ 2] (history! sys))
      (let [before (postgres/event-rows)]
        (dotimes [_ 3]
          (system/replay! sys :compliance)
          (system/settle! sys))
        (is (= before (postgres/event-rows)))
        (is (= 4 (count (postgres/flagged-rows)))
            "and three replays produce the same read model as one — two
             accounts, two flagged movements each")))))

(deftest replaying-an-unknown-module-is-refused-test
  (fixture/with-system
    (fn [sys]
      (is (= :unknown-module
             (:reason (ex-data (try (system/replay! sys :nonsense)
                                    (catch clojure.lang.ExceptionInfo e e)))))))))
