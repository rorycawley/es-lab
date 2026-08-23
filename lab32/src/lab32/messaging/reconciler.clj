(ns lab32.messaging.reconciler
  "A clock, and nothing else.

  §6.6: the reconciler's entire job is to call `dispatcher/drain!` on a timer.
  It contains no delivery logic, no catch-up query, no idea what a message is.
  That is deliberate and it is the thing to preserve if this is ever extended:
  the moment the reconciler knows something the fast path does not, the fast
  path stops being a pure optimisation and the two paths have to be tested
  separately.

  Why it exists at all is Gotcha #3. `NOTIFY` is at-most-once -- if no session
  is listening when it fires, the signal is discarded and no error is raised
  anywhere. That is not a defect to work around; it is the documented
  behaviour, and it is precisely why a system built only on LISTEN/NOTIFY loses
  events during every deploy, every failover and every network blip. The
  reconciler makes a lost signal cost latency instead of correctness.

  This namespace also owns the scheduling used by the inbox workers, because
  \"run this on a timer, forever, and do not die\" is one problem and deserves
  one answer."
  (:require [clojure.tools.logging :as log])
  (:import (java.util.concurrent Executors ScheduledExecutorService
                                 ThreadFactory TimeUnit)))

(defn- daemon-factory
  ^ThreadFactory [label]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable (str "lab32-" label))
        (.setDaemon true)))))

(defn every!
  "Run `f` every `interval-ms`, on one daemon thread. Returns the executor.

  The `try/catch` is not defensive habit, it is mandatory. A task submitted to
  `scheduleAtFixedRate` that throws is **silently cancelled** -- no further
  executions, no log line, no exception anywhere a person will see. A
  reconciler that stopped reconciling three weeks ago looks exactly like a
  reconciler with nothing to do, right up until the first lost NOTIFY, and the
  incident that follows will not obviously be about a swallowed exception.

  Catching inside the task means a failed pass is just a failed pass."
  ^ScheduledExecutorService
  [label interval-ms f]
  (doto (Executors/newSingleThreadScheduledExecutor (daemon-factory label))
    (.scheduleAtFixedRate
     ^Runnable (fn []
                 (try
                   (f)
                   (catch Throwable t
                     (log/warn t label "pass failed; will run again"))))
     interval-ms interval-ms TimeUnit/MILLISECONDS)))

(defn stop!
  [^ScheduledExecutorService executor]
  (when executor
    (.shutdownNow executor)
    (.awaitTermination executor 5 TimeUnit/SECONDS)))
