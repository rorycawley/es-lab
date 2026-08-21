(ns lab23.app
  "The application layer — the **imperative shell**.

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
  `adapter`, and it hands the core plain values."
  (:require [lab23.core.contract :as contract]
            [lab23.core.policy :as policy]
            [lab23.core.truck :as truck]
            [lab23.port.driven :as driven]))

(defn stock
  "A query. Read the stream, fold it — and that fold is the core's."
  [{:keys [store]} truck-id]
  (truck/replay (driven/read-stream store truck-id)))

(defn handle
  "One command: read, decide, append, enqueue.

  Lab 8's four steps, with ports where the vector used to be. Nothing about
  this function knows whether `store` is Postgres or a map in an atom."
  [{:keys [store outbox clock ids]} truck-id command]
  (let [history (driven/read-stream store truck-id)
        version (driven/stream-version store truck-id)
        state   (truck/replay history)                     ; core
        decided (truck/decide command state)               ; core
        events  (driven/append store truck-id version command
                               (map #(assoc % :event/occurred-at (driven/now clock)
                                            :event/id (driven/new-id ids))
                                    decided))
        messages (into [] (mapcat contract/announce) events)] ; core
    (when (seq messages)
      (driven/enqueue outbox (map #(assoc % :message-id (driven/new-id ids)) messages)))
    events))

(defn react
  "One pass of the reactor: read what is new, ask the policy, run what it asks.

  The decision about *what to do* is the core's; the loop, the reading and the
  dispatching are the shell's."
  [{:keys [store] :as app} checkpoint truck-id]
  (let [batch    (driven/read-since store checkpoint)
        commands (policy/react-to-all batch)               ; core
        applied  (doall (for [command commands]
                          (handle app truck-id command)))]
    {:checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :commands   (vec commands)
     :events     (vec (apply concat applied))}))
