(ns lab22.system
  "The composition root: the one place that names concrete things.

  Twenty labs passed `gen-id` and `now` down through every call because there
  was nowhere to put them. Component gives them somewhere — a system map that
  declares what exists and what depends on what, started once at the edge of
  the world and handed to the application layer as data.

  Two rules this file exists to keep:

  **Nothing else constructs an adapter.** Search the repository for
  `postgres/store` and it appears here and nowhere else. That is what makes
  swapping one a one-line change rather than an audit.

  **Nothing in `core` requires this namespace, or `component`, or a port.**
  The core depends on nothing in this application. `app.clj` depends on the
  core and output-port abstractions; concrete adapters implement those
  abstractions; this outer composition root wires the choices together. A test
  asserts those inward-pointing source dependencies rather than trusting a
  diagram."
  (:require [com.stuartsierra.component :as component]
            [lab22.adapter.clock :as clock]
            [lab22.adapter.memory :as memory]
            [lab22.adapter.postgres :as postgres]))

(defn in-memory
  "Everything in process. No Docker, no container, no configuration.

  The demo runs on this, and so does most of the test suite — which is only
  possible because the application layer cannot tell the difference."
  ([] (in-memory {}))
  ([{:keys [clock ids]}]
   (let [selected-clock (or clock (clock/system-clock))]
     (component/system-map
      :clock selected-clock
      :store (component/using (memory/store) {:clock :clock})
      :ids   (or ids (clock/random-ids))))))

(defn with-postgres
  "The same system, one line different.

  One Postgres adapter owns event, ledger and outbox writes so the command
  outcome retains a single transaction boundary."
  ([config] (with-postgres config {}))
  ([config {:keys [clock ids]}]
   (component/system-map
    :database (postgres/database config)
    :store    (component/using (postgres/store) {:datasource :database})
    :clock    (or clock (clock/system-clock))
    :ids      (or ids (clock/random-ids)))))

(defn app
  "The application layer's dependencies, taken from a started system.

  `app.clj` receives a plain map — it never sees the system, never calls
  `component/start`, and could not tell you which adapter it was handed."
  [system]
  (assoc (select-keys system [:store :clock :ids]) :outbox (:store system)))

(defn start [system] (component/start system))
(defn stop [system] (component/stop system))
