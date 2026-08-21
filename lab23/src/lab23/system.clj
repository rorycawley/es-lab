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
  The dependency arrows all point inward: shell → ports → core, and never
  back. A test asserts it rather than trusting it."
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
   (component/system-map
    :store  (memory/store)
    :outbox (memory/outbox)
    :clock  (or clock (clock/system-clock))
    :ids    (or ids (clock/random-ids)))))

(defn with-postgres
  "The same system, one line different.

  `store` and `outbox` become Postgres records that need a `datasource`, and
  Component supplies it — which is the entire difference between this function
  and the one above."
  ([config] (with-postgres config {}))
  ([config {:keys [clock ids]}]
   (component/system-map
    :database (postgres/database config)
    :store    (component/using (postgres/store) {:datasource :database})
    :outbox   (component/using (postgres/outbox) {:datasource :database})
    :clock    (or clock (clock/system-clock))
    :ids      (or ids (clock/random-ids)))))

(defrecord HttpServer [port store outbox clock ids server]
  component/Lifecycle
  (start [this]
    ;; Jetty is required here and nowhere else — `system.clj` is the only file
    ;; allowed to name a concrete adapter, and a web server is an adapter like
    ;; any other. It has a lifecycle, so Component owns it.
    (let [run (requiring-resolve 'ring.adapter.jetty/run-jetty)
          deps (select-keys this [:store :outbox :clock :ids])]
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
                                     [:store :outbox :clock :ids])))

(defn app
  "The application layer's dependencies, taken from a started system.

  `app.clj` receives a plain map — it never sees the system, never calls
  `component/start`, and could not tell you which adapter it was handed."
  [system]
  (select-keys system [:store :outbox :clock :ids]))

(defn start [system] (component/start system))
(defn stop [system] (component/stop system))
