(ns lab32.ordering-test
  "Phase 3 — per-aggregate ordering.

  Acceptance tests 11 and 12."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.fixture :as fixture]
            [lab32.messaging.dispatcher :as dispatcher]
            [lab32.messaging.router :as router]
            [lab32.postgres :as postgres]
            [lab32.system :as system]))

(def ^:private aggregates 20)
(def ^:private per-aggregate 25)

(defn- enqueue-workload!
  "500 movements across 20 accounts, written by four threads.

  The accounts are opened first and serially, so that the write path's own
  retry loop is not what this test is measuring -- the subject is the
  dispatcher, and a workload that spent its time losing version races would
  hide that."
  [sys]
  (let [module   (system/accounts-module sys)
        accounts (repeatedly aggregates random-uuid)]
    (doseq [account accounts]
      (accounts/open-account! module {:account-id account :holder "Ada"}))
    (run! deref
          (mapv (fn [group]
                  (future
                    (doseq [account group
                            i (range per-aggregate)]
                      (accounts/deposit! module {:account-id account
                                                 :amount (+ 100 i)}))))
                (partition-all 5 accounts)))
    (vec accounts)))

(defn- delivered-order
  "For each partition: the event ids in outbox order, and in inbox order."
  []
  (let [by-partition (fn [rows] (group-by :partition-key rows))
        outbox (by-partition (postgres/outbox-rows))
        inbox  (by-partition (postgres/inbox-rows))]
    (into {}
          (for [[partition rows] outbox]
            [partition
             {:published (mapv :event-id (sort-by :seq rows))
              :delivered (mapv :event-id (sort-by :seq (get inbox partition [])))}]))))

(defn- inversions
  "Partitions whose inbox order does not match their outbox order."
  [orders]
  (for [[partition {:keys [published delivered]}] orders
        :when (not= published delivered)]
    partition))

(defn- drain-with!
  "Drain the outbox using `threads` independent dispatchers. Returns wall-clock
  milliseconds.

  Independent, not shared: each gets its own semaphore and its own `rerun`
  flag, so they contend through the database exactly as separate deployments
  would rather than coalescing inside one JVM."
  [sys strategy threads]
  (let [config (assoc (:dispatcher (postgres/config)) :claim-strategy strategy)
        routes (router/router system/contracts)
        pool   (system/pool-for (:datasources sys) :messaging)
        made   (mapv (fn [_] (dispatcher/dispatcher pool routes config)) (range threads))
        start  (System/nanoTime)]
    (run! deref (mapv (fn [d] (future (dotimes [_ 12] (dispatcher/drain! d)))) made))
    (quot (- (System/nanoTime) start) 1000000)))

;; ---------------------------------------------------------------------------
;; Acceptance test 11
;; ---------------------------------------------------------------------------

(deftest partition-claiming-preserves-per-aggregate-order-test
  (fixture/with-system
    (fn [sys]
      (enqueue-workload! sys)
      (is (= (* aggregates per-aggregate) (count (postgres/outbox-rows))))

      (drain-with! sys :partition 8)

      (let [orders (delivered-order)]
        (testing "everything was delivered"
          (is (= aggregates (count orders)))
          (is (= (* aggregates per-aggregate) (count (postgres/inbox-rows))))
          (is (zero? (count (postgres/query
                             "SELECT * FROM messaging.outbox WHERE status = 'PENDING'")))))

        (testing "and every aggregate arrived in the order it was published"
          (is (empty? (inversions orders))))))))

(deftest skip-locked-makes-no-such-promise-test
  ;; Gotcha #8, demonstrated rather than asserted.
  ;;
  ;; The honest thing to assert here is what `:skip-locked` *does* guarantee --
  ;; that everything arrives exactly once -- and to report the ordering rather
  ;; than require it to be wrong. A test asserting "this is out of order" would
  ;; pass because eight threads usually interleave and fail on the run where
  ;; they happen not to, which is a flaky test dressed up as a proof.
  ;;
  ;; The number it prints is the point: run it a few times and watch it move.
  (fixture/with-system
    (fn [sys]
      (enqueue-workload! sys)
      (drain-with! sys :skip-locked 8)

      (let [out-of-order (inversions (delivered-order))]
        (println (format "    skip-locked: %d of %d aggregates delivered out of order"
                         (count out-of-order) aggregates))

        (testing "delivery is still exactly-once; only the order is unpromised"
          (is (= (* aggregates per-aggregate) (count (postgres/inbox-rows))))
          (is (= (* aggregates per-aggregate)
                 (count (distinct (map :event-id (postgres/inbox-rows)))))))))))

(deftest the-inbox-worker-preserves-the-order-too-test
  ;; Ordering the delivery into the inbox is only half the job. If the worker
  ;; takes messages off in any order the ordering is lost between the inbox and
  ;; the projection, and nothing downstream would ever reveal it -- the read
  ;; model here is a set of independent rows.
  ;;
  ;; So the handler records what it was given, in the order it was given it.
  (let [seen (atom [])]
    (fixture/with-system
      {:options {:handlers {:compliance (fn [_tx message]
                                          (swap! seen conj message))}}}
      (fn [sys]
        (enqueue-workload! sys)
        (drain-with! sys :partition 4)
        ;; Four workers, running until the inbox is empty. `batch-size` bounds
        ;; how much one pass does -- with 25 messages to a partition it gets
        ;; through two of them -- so a fixed number of passes would leave most
        ;; of the workload untouched and the assertion below would be about
        ;; three partitions rather than twenty.
        (loop [guard 40]
          (let [passes (mapv (fn [_] (future (system/work-inboxes! sys))) (range 4))
                moved  (reduce + (for [pass passes
                                       [_ outcome] @pass]
                                   (:handled outcome)))]
            (when (and (pos? moved) (pos? guard))
              (recur (dec guard)))))

        (let [published (into {} (for [[partition rows] (group-by :partition-key
                                                                  (postgres/outbox-rows))]
                                   [partition (mapv :event-id (sort-by :seq rows))]))
              handled   (into {} (for [[partition messages] (group-by :partition-key @seen)]
                                   [partition (mapv :event-id messages)]))]
          (is (= (count published) (count handled)) "every partition was handled")
          (doseq [[partition order] published]
            (is (= order (get handled partition))
                (str "partition " partition " was handled out of order"))))))))

(deftest a-claimed-partition-is-never-claimed-twice-test
  ;; A regression test for the subtlest bug in the lab, described at length in
  ;; `outbox/claim-partitions!`.
  ;;
  ;; The claim's `status = 'PENDING'` test originally lived only in a CTE. A
  ;; second claimer selected the same rows under its own snapshot, blocked on
  ;; the row lock, and -- because READ COMMITTED rechecks only the UPDATE's own
  ;; WHERE clause when the lock is granted -- went on to update rows that were
  ;; already PROCESSED and return them. Every message was then handled twice.
  ;;
  ;; It was nearly invisible: the inbox and the projection are both keyed on
  ;; `event_id`, so the duplicate was absorbed and the only symptom was an
  ;; ordering test failing about one run in four. Counting handler calls is
  ;; what makes it visible.
  (let [calls (atom 0)]
    (fixture/with-system
      {:options {:handlers {:compliance (fn [_tx _message] (swap! calls inc))}}}
      (fn [sys]
        (enqueue-workload! sys)
        (drain-with! sys :partition 8)
        (is (= (* aggregates per-aggregate) (count (postgres/inbox-rows)))
            "the dispatcher delivered each message once")

        (loop [guard 40]
          (let [passes (mapv (fn [_] (future (system/work-inboxes! sys))) (range 6))
                moved  (reduce + (for [pass passes
                                       [_ outcome] @pass]
                                   (:handled outcome)))]
            (when (and (pos? moved) (pos? guard))
              (recur (dec guard)))))

        (is (= (* aggregates per-aggregate) @calls)
            "the handler ran once per message; more means a partition was claimed twice")))))

;; ---------------------------------------------------------------------------
;; Acceptance test 12 — ordering did not cost the parallelism
;; ---------------------------------------------------------------------------

(deftest different-aggregates-still-process-in-parallel-test
  ;; The failure mode this guards against is a partition scheme that is
  ;; correct and useless: take one lock for everything, deliver in perfect
  ;; order, and serialise the entire system. Ordering per aggregate is only
  ;; worth having if unrelated aggregates still move at the same time.
  (let [serial   (atom nil)
        parallel (atom nil)]

    (fixture/with-system
      (fn [sys]
        (enqueue-workload! sys)
        (reset! serial (drain-with! sys :partition 1))
        (is (= (* aggregates per-aggregate) (count (postgres/inbox-rows))))))

    (fixture/with-system
      (fn [sys]
        (enqueue-workload! sys)
        (reset! parallel (drain-with! sys :partition 8))
        (is (= (* aggregates per-aggregate) (count (postgres/inbox-rows))))))

    (println (format "    partition claiming: %dms single-threaded, %dms with 8 threads"
                     @serial @parallel))

    ;; Not a speedup assertion, and the measured numbers are usually a dead
    ;; heat. At this workload each delivery is a single local INSERT inside an
    ;; already-open transaction, so wall time is dominated by round trips that
    ;; do not parallelise, and eight threads mostly contend. Lab 31 is an
    ;; entire lab about why a number like this means nothing without a declared
    ;; workload and environment.
    ;;
    ;; What can be said honestly is that throughput did not *collapse* -- which
    ;; is exactly what a global lock masquerading as a partition scheme would
    ;; do. The test below asserts the concurrency itself rather than inferring
    ;; it from a stopwatch.
    (is (< @parallel (* 2 @serial))
        (str "eight threads should not be dramatically slower than one: "
             @parallel "ms vs " @serial "ms"))))

(deftest two-partitions-are-worked-at-the-same-moment-test
  ;; The property acceptance test 12 is really about, asserted directly instead
  ;; of inferred from wall clock.
  ;;
  ;; Two handlers, on two different accounts, each count down a latch of two
  ;; and then wait for it. If unrelated partitions genuinely progress at the
  ;; same time, both are inside the handler together and both waits succeed. If
  ;; the scheme serialised everything -- one global lock, or a partition key
  ;; that collapses to one value -- the first handler waits alone until it
  ;; times out, and the assertion fails.
  ;;
  ;; A latch rather than a stopwatch, so the result is a fact about
  ;; concurrency rather than a number about a laptop.
  (let [latch   (java.util.concurrent.CountDownLatch. 2)
        results (atom [])]
    (fixture/with-system
      {:options {:handlers
                 {:compliance (fn [_tx _message]
                                (.countDown latch)
                                (swap! results conj
                                       (.await latch 5 java.util.concurrent.TimeUnit/SECONDS)))}}}
      (fn [sys]
        (let [module (system/accounts-module sys)]
          (doseq [_ (range 2)]
            (let [account (random-uuid)]
              (accounts/open-account! module {:account-id account :holder "Ada"})
              (accounts/deposit! module {:account-id account :amount 12000})))
          (system/dispatch! sys)
          (is (= 2 (count (postgres/inbox-rows))))

          (run! deref (mapv (fn [_] (future (system/work-inboxes! sys))) (range 2)))

          (is (= 2 (count @results)) "both messages were handled")
          (is (every? true? @results)
              "the two partitions were not inside their handlers at the same time"))))))
