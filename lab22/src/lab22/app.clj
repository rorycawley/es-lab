(ns lab22.app
  "The application layer — the **imperative shell** and use-case surface.

  Read this looking for business logic. There is none. Every function here has
  the same shape:

      read something          ← a port
      call a pure function    ← the core
      write something         ← a port

  That shape is the whole discipline, and its thinness is the measure. The
  moment a conditional appears here that is not about I/O, a rule has leaked
  out of the core and into a place that cannot be tested without a database.

  Ports-and-adapters calls this the application or service layer. It is the
  only code that knows both worlds: it depends on `port`, never on any
  `adapter`, and it hands the core plain values. `handle`, `stock` and `react`
  are ordinary Clojure functions and also the driving/input ports: tests, the
  demo and intake adapters call them without needing a protocol."
  (:require [lab22.core.contract :as contract]
            [lab22.core.policy :as policy]
            [lab22.core.truck :as truck]
            [lab22.port :as port]))

(defn stock
  "A query. Read the stream, fold it — and that fold is the core's."
  [{:keys [store]} truck-id]
  (truck/replay (port/read-stream store truck-id)))

(defn handle
  "One command: read, decide, append, enqueue.

  Lab 8's four steps, with ports where the vector used to be. Nothing about
  this function knows whether `store` is Postgres or a map in an atom."
  [{:keys [store outbox clock ids]} truck-id command]
  (let [history (port/read-stream store truck-id)
        version (port/stream-version store truck-id)
        state   (truck/replay history)                     ; core
        decided (truck/decide command state)               ; core
        events  (port/append store truck-id version command
                             (map #(assoc % :event/occurred-at (port/now clock)
                                          :event/id (port/new-id ids))
                                  decided))
        messages (into [] (mapcat contract/announce) events)] ; core
    (when (seq messages)
      (port/enqueue outbox (map #(assoc % :message-id (port/new-id ids)) messages)))
    events))

(defn react
  "One pass of the reactor: read what is new, ask the policy, run what it asks.

  The decision about *what to do* is the core's; the loop, the reading and the
  dispatching are the shell's."
  [{:keys [store] :as app} checkpoint truck-id]
  (let [batch    (port/read-since store checkpoint)
        commands (policy/react-to-all batch)               ; core
        applied  (doall (for [command commands]
                          (handle app truck-id command)))]
    {:checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   (vec commands)
     :events     (vec (apply concat applied))}))
