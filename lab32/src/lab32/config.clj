(ns lab32.config
  "Configuration, and the two switches the build phases hang off.

  Most of this is ordinary knob-turning. Two entries are not, and they are
  here rather than hard-coded because the lab's central claim is only
  demonstrable if they can be turned off:

    [:listener :enabled?]        Phase 2's fast path. Acceptance test 9 starts
                                 the system with this false and a database with
                                 no NOTIFY trigger in it, and re-runs the whole
                                 Phase 1 suite. If anything fails, the fast path
                                 was never an optimisation -- it was load
                                 bearing, and the reconciler was decoration.

    [:dispatcher :claim-strategy]  Phase 1 claims individual rows with
                                 SKIP LOCKED and makes no ordering promise
                                 (Gotcha #8). Phase 3 claims whole partitions
                                 under an advisory lock and does.
                                 `ordering_test.clj` runs the same workload
                                 through both, which is the only honest way to
                                 show that the second one bought something."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def defaults
  {:database   {:jdbc-url   "jdbc:postgresql://localhost:5432/eslab"
                :accounts   {:user "accounts_module"   :password "accounts-pass"}
                :compliance {:user "compliance_module" :password "compliance-pass"}
                :messaging  {:user "messaging_module"  :password "messaging-pass"}
                :admin      {:user "postgres"          :password "postgres"}}

   :dispatcher {:batch-size     50
                ;; Phase 3's default. `:skip-locked` is the Phase 1 strategy
                ;; and is kept because `ordering_test.clj` runs the same
                ;; workload through both -- a claim that this bought something
                ;; is worth more when you can watch the alternative fail.
                :claim-strategy :partition
                ;; How many partitions one claim transaction may lock. Bounds
                ;; the time an advisory lock is held, which bounds how long a
                ;; competing dispatcher waits for a partition it wants.
                :partition-limit 20
                ;; Not a retry budget for a transient failure -- the retry
                ;; happens inside one attempt. This is how many separate
                ;; attempts a message gets before we conclude the problem is
                ;; the message and not the moment.
                :max-attempts   20}

   ;; 10 seconds, and Revolut's own reconciler resends anything unpublished for
   ;; ~30. The number is not the interesting part. What matters is that this
   ;; interval is the system's *worst case* latency and never its typical one,
   ;; and that raising it costs latency but never correctness.
   :reconciler {:interval-ms 10000}

   :listener   {:enabled?        false
                :channel         "outbox_events"
                ;; The blocking overload of `getNotifications`. Zero-arg does
                ;; no network I/O at all (Gotcha #1), so a loop around it spins
                ;; a core receiving nothing.
                :poll-timeout-ms 30000
                ;; A poll that times out cannot tell a quiet channel from a
                ;; dead socket. This is the difference.
                :health-check-ms 60000
                :backoff-ms      1000}

   ;; The consumer side of Phase 3. Ordering the delivery into the inbox is
   ;; only half of it: if the worker takes messages off in any order, or two
   ;; workers take them concurrently, the order the dispatcher preserved is
   ;; lost between the inbox and the projection.
   :inbox      {:batch-size 50 :max-attempts 20 :claim-strategy :partition}

   ;; §7. Hourly sweep, 24 hours kept -- of the *queues*. Note there is no
   ;; entry here for the event stream, and there is no code that could prune
   ;; it. That is the difference the whole lab argues for: a broker's retention
   ;; window is the only durability it has, and here it applies to the work
   ;; list while the history it was about is kept forever.
   :retention  {:interval-ms 3600000 :hours 24}

   :http       {:port 3000}})

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn configure
  "`defaults`, overlaid with `resources/config.edn` if present, overlaid with
  `overrides`.

  The suite passes overrides rather than writing a file, because the JDBC URL
  it needs does not exist until a container has started."
  ([] (configure {}))
  ([overrides]
   (let [from-file (some-> (io/resource "config.edn") slurp edn/read-string)]
     (deep-merge (deep-merge defaults (or from-file {})) overrides))))

(defn connection
  "The `{:jdbc-url :user :password}` one identity connects with."
  [config identity]
  (let [{:keys [jdbc-url] :as database} (:database config)]
    (assoc (get database identity) :jdbc-url jdbc-url)))
