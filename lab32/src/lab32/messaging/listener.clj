(ns lab32.messaging.listener
  "One thread, one connection, and a bell.

  This namespace exists entirely because of Gotcha #1, which is worth quoting
  because it is the constraint the whole design bends around. The pgjdbc
  documentation: *\"A key limitation of the JDBC driver is that it cannot
  receive asynchronous notifications and must poll the backend.\"* There is no
  callback. Three consequences follow, and each is a way to get this wrong.

  **The connection cannot come from the pool.** `LISTEN` is session state. A
  pooled connection is returned, reset, and handed to somebody else, and the
  subscription goes with it -- silently, because a session that is not
  listening looks exactly like a channel that is quiet. So this opens its own
  connection directly and holds it for the process's lifetime.

  **The poll has to be the blocking overload.** `getNotifications()` with no
  argument does no network I/O at all: it returns whatever has already been
  buffered, which for an idle channel is nothing. A `while(true)` around it
  spins a core receiving nothing forever. `getNotifications(timeoutMillis)`
  actually waits.

  **A poll that times out cannot tell a quiet channel from a dead socket.**
  Nothing about a silent 30-second timeout distinguishes \"no deposits\" from
  \"the TCP connection died an hour ago\", so a periodic `SELECT 1` is the only
  thing that will notice. This is the one most likely to be skipped, because
  skipping it appears to work: the reconciler keeps delivering everything, so
  a permanently dead listener shows up as latency nobody can explain rather
  than as an error anybody can see.

  And the rule that ties it together: **every reconnect must re-LISTEN and
  then immediately drain.** Notifications sent while disconnected are gone
  (Gotcha #3), so a listener that resubscribes without catching up has a hole
  in it exactly the width of its own outage."
  (:require [clojure.tools.logging :as log])
  (:import (java.sql Connection DriverManager)
           (org.postgresql PGConnection)))

(defn- connect
  ^Connection [{:keys [jdbc-url user password]}]
  (doto (DriverManager/getConnection jdbc-url user password)
    (.setAutoCommit true)))

(defn- execute!
  [^Connection connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))

(defn- listen-loop
  "Subscribe, catch up, then block on the channel until told to stop.

  `channel` is interpolated because `LISTEN` will not take a bind parameter.
  It comes from this application's own configuration and never from anything a
  request can reach."
  [^Connection connection {:keys [channel poll-timeout-ms health-check-ms]} kick! running?]
  (execute! connection (str "LISTEN " channel))

  ;; The mandatory catch-up, and the reason it is not optional: between the
  ;; last poll of the previous connection and this LISTEN, any number of
  ;; messages may have been enqueued and their notifications discarded. Without
  ;; this line they wait for the reconciler -- which is correct, and is the
  ;; multi-second latency spike the fast path was added to remove.
  (kick!)

  (let [pg (.unwrap connection PGConnection)]
    (loop [checked-at (System/currentTimeMillis)]
      (when @running?
        (when (seq (.getNotifications pg poll-timeout-ms))
          (kick!))
        (let [now (System/currentTimeMillis)]
          (if (>= (- now checked-at) health-check-ms)
            (do (execute! connection "SELECT 1")
                (recur now))
            (recur checked-at)))))))

(defn- supervise
  "Run the listen loop forever, reconnecting with backoff."
  [connection-config {:keys [backoff-ms] :or {backoff-ms 1000} :as listener-config}
   kick! running? current]
  (loop [backoff backoff-ms]
    (when @running?
      (let [failed? (try
                      (let [connection (connect connection-config)]
                        (reset! current connection)
                        (try
                          (listen-loop connection listener-config kick! running?)
                          false
                          (finally
                            (reset! current nil)
                            (try (.close connection) (catch Exception _ nil)))))
                      (catch Throwable t
                        ;; Expected during shutdown: `stop!` closes the
                        ;; connection out from under the blocking poll, which
                        ;; is the only way to interrupt it promptly.
                        (when @running?
                          (log/warn t "listener connection lost; reconnecting"))
                        true))]
        (when (and @running? failed?)
          (Thread/sleep backoff)
          (recur (min 30000 (* 2 backoff))))))))

(defn start!
  "Begin listening. `kick!` is called whenever there may be work.

  Returns a handle for `stop!`. The thread is a daemon, so a JVM that forgets
  to stop it still exits."
  [connection-config listener-config kick!]
  (let [running? (atom true)
        current  (atom nil)
        run      ^Runnable (fn []
                             (supervise connection-config listener-config
                                        kick! running? current))
        thread   (doto (Thread. run "lab32-notify-listener")
                   (.setDaemon true)
                   (.start))]
    {:thread thread :running? running? :connection current}))

(defn stop!
  [{:keys [^Thread thread running? connection]}]
  (when running?
    (reset! running? false)
    ;; Closing the connection is what unblocks `getNotifications`. Interrupting
    ;; the thread does not: the driver is blocked on a socket read, and it will
    ;; sit there until the poll timeout expires -- up to thirty seconds of a
    ;; test suite waiting to shut down.
    (when-let [^Connection open @connection]
      (try (.close open) (catch Exception _ nil)))
    (when thread
      (.interrupt thread)
      (.join thread 5000))))
