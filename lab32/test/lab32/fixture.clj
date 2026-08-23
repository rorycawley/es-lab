(ns lab32.fixture
  "A started system against a real Postgres, and the helpers every test needs.

  `with-system` starts and stops the whole component map per scenario. That is
  more work than labs 25 to 29 did -- they built a map of closures and threw it
  away -- and it is the price of having things that run. It also buys
  something: `component/stop` failing is itself a test, because a lifecycle
  that cannot be unwound is one that leaks.

  The table-reading helpers are re-exported from `lab32.postgres` rather than
  defined here, because the demo needs them too and lives on a different
  alias. Tests say `fixture/outbox-rows` because that is where a reader looks
  first."
  (:require [com.stuartsierra.component :as component]
            [lab32.postgres :as postgres]
            [lab32.system :as system]))

(defn with-system
  "Run `f` with a started system. Both schedulers are off; see
  `postgres/overrides`."
  ([f] (with-system {} f))
  ([{:keys [config options]} f]
   (postgres/truncate!)
   ;; The trigger is a database object and outlives any one system, so a test
   ;; that disabled it would leave it disabled for whatever ran next. Put it
   ;; back before every scenario rather than trusting each one to tidy up.
   (postgres/set-notify-trigger! true)
   (let [started (system/start (postgres/config (or config {})) (or options {}))]
     (try
       (f started)
       (finally
         (component/stop started))))))

(defn wait-for
  "Poll `check` until it returns something truthy, or give up.

  Returns `[value elapsed-ms]`, or `[nil elapsed-ms]` on timeout. Tests about
  the fast path have to measure rather than assert immediately: the whole
  claim is about *when* something arrives, and the arrival happens on another
  thread."
  ([check] (wait-for check 5000))
  ([check timeout-ms]
   (let [started (System/nanoTime)
         deadline (+ (System/currentTimeMillis) timeout-ms)
         elapsed #(quot (- (System/nanoTime) started) 1000000)]
     (loop []
       (if-let [found (check)]
         [found (elapsed)]
         (if (< (System/currentTimeMillis) deadline)
           (do (Thread/sleep 2) (recur))
           [nil (elapsed)]))))))

(defn percentile
  "Nearest-rank percentile, as lab 31 defined it."
  [samples p]
  (let [sorted (vec (sort samples))
        rank   (max 1 (long (Math/ceil (* (/ p 100.0) (count sorted)))))]
    (nth sorted (dec rank))))

(def query            postgres/query)
(def outbox-rows      postgres/outbox-rows)
(def inbox-rows       postgres/inbox-rows)
(def dead-letter-rows postgres/dead-letter-rows)
(def event-rows       postgres/event-rows)
(def flagged-rows     postgres/flagged-rows)

(defn accounts   [system] (system/accounts-module system))
(defn compliance [system] (system/compliance-module system))
