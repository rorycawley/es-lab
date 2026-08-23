(ns lab32.fast-path-test
  "Phase 2 — the fast path, and the proof that it is only that.

  Acceptance tests 7, 8, 9 and 10."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.fixture :as fixture]
            [lab32.messaging.dispatcher :as dispatcher]
            [lab32.messaging.router :as router]
            [lab32.postgres :as postgres]
            [lab32.scenarios :as scenarios]
            [lab32.system :as system]))

(defn- deposit!
  "Open an account and deposit, returning the moment the command committed."
  [sys amount]
  (let [account (random-uuid)]
    (accounts/open-account! (system/accounts-module sys)
                            {:account-id account :holder "Ada"})
    (accounts/deposit! (system/accounts-module sys) {:account-id account :amount amount})
    account))

(defn- inbox-count [] (count (postgres/inbox-rows)))

;; ---------------------------------------------------------------------------
;; Acceptance test 9 — the fast path is a pure optimisation
;;
;; The most important test in the lab, and the reason `scenarios.clj` exists.
;; ---------------------------------------------------------------------------

(def ^:private configurations
  [["no trigger, no listener"     {:trigger? false :listener? false}]
   ["trigger, nobody listening"   {:trigger? true  :listener? false}]
   ["trigger and listener"        {:trigger? true  :listener? true}]])

(deftest every-phase-one-property-holds-in-every-configuration-test
  (doseq [[label {:keys [trigger? listener?]}] configurations
          scenario scenarios/every-scenario]
    (fixture/with-system
      {:config {:listener {:enabled? listener?}}}
      (fn [sys]
        (postgres/set-notify-trigger! trigger?)
        (is (= trigger? (postgres/notify-trigger-enabled?)))
        (testing label
          (scenario sys))))))

(deftest the-trigger-really-was-off-test
  ;; A guard on the guard. If `set-notify-trigger!` silently did nothing, the
  ;; test above would pass three identical configurations and prove nothing.
  (fixture/with-system
    (fn [_sys]
      (postgres/set-notify-trigger! false)
      (is (not (postgres/notify-trigger-enabled?)))
      (postgres/set-notify-trigger! true)
      (is (postgres/notify-trigger-enabled?)))))

;; ---------------------------------------------------------------------------
;; Acceptance test 7 — latency
;; ---------------------------------------------------------------------------

(def ^:private slow-reconciler-ms
  "Deliberately slow, so the comparison is legible in a test that has to
  finish. In production this is ten seconds and the gap is larger, not
  smaller."
  2000)

(defn- latencies
  "How long each of `n` deposits took to appear in the inbox."
  [sys n]
  (mapv (fn [i]
          (let [before (inbox-count)]
            (deposit! sys 12000)
            (let [[_ elapsed] (fixture/wait-for #(> (inbox-count) before) 8000)]
              (is (= (inc before) (inbox-count))
                  (str "deposit " i " never arrived"))
              elapsed)))
        (range n)))

(deftest the-fast-path-is-faster-than-the-reconciler-test
  ;; The assertion is an *improvement*, not an absolute number, because an
  ;; absolute number measured on a laptop under a container is a number about
  ;; the laptop. Lab 31 spent a whole lab on why: a latency claim needs a
  ;; declared workload, environment and metric, and this one has none of those.
  ;; What it can honestly say is that the same workload, on the same machine,
  ;; in the same minute, got dramatically quicker.
  (let [samples 5
        slow (atom nil)
        fast (atom nil)]

    (testing "with only the reconciler, latency is the polling interval"
      (fixture/with-system
        {:config {:reconciler {:interval-ms slow-reconciler-ms}
                  :inbox      {:interval-ms 25}
                  :listener   {:enabled? false}}}
        (fn [sys]
          (postgres/set-notify-trigger! false)
          (reset! slow (latencies sys samples)))))

    (testing "with the doorbell, it is the time to open the door"
      (fixture/with-system
        {:config {:reconciler {:interval-ms slow-reconciler-ms}
                  :inbox      {:interval-ms 25}
                  :listener   {:enabled? true}}}
        (fn [sys]
          (reset! fast (latencies sys samples)))))

    (let [slow-p99 (fixture/percentile @slow 99)
          fast-p99 (fixture/percentile @fast 99)]
      (println (format "    reconciler only: p99 %dms   with NOTIFY: p99 %dms"
                       slow-p99 fast-p99))
      (is (< fast-p99 (/ slow-p99 4))
          (str "the fast path should be several times quicker: "
               @fast " vs " @slow))
      (is (< fast-p99 500)
          "and should be responding to a signal rather than to a timer"))))

;; ---------------------------------------------------------------------------
;; Acceptance test 8 — kill the listener's connection
;; ---------------------------------------------------------------------------

(defn- terminate-listener-connection!
  "Kill the backend holding the LISTEN, from outside the application.

  `pg_terminate_backend` on the session that is subscribed to the channel.
  This is what a network partition, a failover or an idle-connection reaper
  does to a long-lived connection, and it is invisible to a poll that is
  waiting for a notification that will never come."
  []
  (postgres/query "SELECT pg_terminate_backend(pid)
                     FROM pg_stat_activity
                    WHERE query LIKE 'LISTEN%'
                      AND pid <> pg_backend_pid()"))

(deftest killing-the-listener-connection-loses-no-events-test
  (fixture/with-system
    {:config {:reconciler {:interval-ms 500}
              :inbox      {:interval-ms 25}
              :listener   {:enabled? true :backoff-ms 50}}}
    (fn [sys]
      (deposit! sys 12000)
      (is (first (fixture/wait-for #(= 1 (inbox-count)) 5000))
          "the fast path is working before anything is broken")

      (terminate-listener-connection!)

      (testing "delivery continues while the listener reconnects"
        (deposit! sys 13000)
        (is (first (fixture/wait-for #(= 2 (inbox-count)) 8000))
            "the reconciler covers the gap"))

      (testing "and the listener comes back"
        ;; Not asserted by timing -- that would be a race. The reconnect is
        ;; observable in the database: a session is subscribed again.
        (is (first (fixture/wait-for
                    #(pos? (:count (postgres/query-one
                                    "SELECT count(*) AS count FROM pg_stat_activity
                                      WHERE query LIKE 'LISTEN%'")))
                    8000))))

      (testing "nothing was delivered twice"
        ;; Wait for the *projection*, not just the delivery. The inbox worker
        ;; is a separate scheduled pass, so a message can be in the inbox and
        ;; not yet in the read model -- asserting on the read model straight
        ;; after waiting for the inbox is a race, and it fails about one run
        ;; in five.
        (fixture/wait-for #(= 2 (count (postgres/flagged-rows))) 8000)
        (is (= 2 (count (postgres/inbox-rows))))
        (is (= 2 (count (postgres/flagged-rows))))
        (is (= 2 (count (distinct (map :event-id (postgres/inbox-rows))))))))))

;; ---------------------------------------------------------------------------
;; Acceptance test 10 — two instances, one database
;; ---------------------------------------------------------------------------

(deftest two-dispatchers-never-deliver-the-same-event-twice-test
  ;; Two dispatchers with their own pools, their own semaphores and their own
  ;; `rerun` flags. That is what two JVMs are, minus the process boundary --
  ;; and the process boundary is not what makes this hard. The coalescing
  ;; semaphore is per-JVM by construction, so two of them contend exactly as
  ;; two deployments would, and the only thing standing between them is
  ;; `FOR UPDATE SKIP LOCKED` and the inbox's unique constraint.
  (fixture/with-system
    (fn [sys]
      (let [module (system/accounts-module sys)
            events 40]
        (dotimes [_ events]
          (let [account (random-uuid)]
            (accounts/open-account! module {:account-id account :holder "Ada"})
            (accounts/deposit! module {:account-id account :amount 12000})))
        (is (= events (count (postgres/outbox-rows))))

        (let [config (postgres/config)
              router (router/router system/contracts)
              second-instance (dispatcher/dispatcher
                               (system/pool-for (:datasources sys) :messaging)
                               router
                               (:dispatcher config))
              first-instance  (get-in sys [:dispatcher :instance])]

          (run! deref [(future (dotimes [_ 5] (dispatcher/drain! first-instance)))
                       (future (dotimes [_ 5] (dispatcher/drain! second-instance)))])

          (testing "every event was delivered"
            (is (= events (count (postgres/inbox-rows)))))

          (testing "and none of them twice"
            (is (= events (count (distinct (map :event-id (postgres/inbox-rows))))))
            (is (zero? (count (postgres/query
                               "SELECT * FROM messaging.outbox WHERE status = 'PENDING'")))))

          (testing "and each outbox row was claimed exactly once"
            (is (every? #(= 1 (:attempts %)) (postgres/outbox-rows))
                "an attempt count above one means two dispatchers claimed the same row")))))))
