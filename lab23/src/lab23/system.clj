(ns lab23.system
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
            [lab23.adapter.clock :as clock]
            [lab23.adapter.http :as http]
            [lab23.adapter.memory :as memory]
            [lab23.adapter.postgres :as postgres]))

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

(defrecord HttpServer [port store clock ids server]
  component/Lifecycle
  (start [this]
    ;; Jetty is required here and nowhere else — `system.clj` is the only file
    ;; allowed to name a concrete adapter, and a web server is an adapter like
    ;; any other. It has a lifecycle, so Component owns it.
    (let [run (requiring-resolve 'ring.adapter.jetty/run-jetty)
          deps (assoc (select-keys this [:store :clock :ids]) :outbox store)]
      (assoc this :server (run (http/handler deps) {:port port :join? false}))))
  (stop [this]
    (when server (.stop server))
    (assoc this :server nil)))

(defn serving
  "`base` plus an HTTP server on `port`.

  Note that the server *depends on* the same components the application uses.
  It does not construct them, and it does not reach for them — Component
  supplies them, exactly as it supplies a datasource to a store."
  [base port]
  (assoc base :http (component/using (map->HttpServer {:port port})
                                     [:store :clock :ids])))

(defn app
  "The application layer's dependencies, taken from a started system.

  `app.clj` receives a plain map — it never sees the system, never calls
  `component/start`, and could not tell you which adapter it was handed."
  [system]
  (assoc (select-keys system [:store :clock :ids]) :outbox (:store system)))

(defn start [system] (component/start system))
(defn stop [system] (component/stop system))
