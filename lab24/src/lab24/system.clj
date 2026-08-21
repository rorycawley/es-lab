(ns lab24.system
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
  The core depends on nothing in this application. The application depends on
  the core and output ports; this outer root wires concrete choices."
  (:require [com.stuartsierra.component :as component]
            [lab24.adapter.clock :as clock]
            [lab24.adapter.http :as http]
            [lab24.adapter.memory :as memory]
            [lab24.adapter.oidc :as oidc]
            [lab24.adapter.postgres :as postgres]))

;; ---------------------------------------------------------------------------
;; What is NOT in this file, and the reason it is not
;;
;; There is no identity provider component. The provider is somebody else's
;; system — the thing you are integrating *with* — and putting it in your
;; system map would say the opposite. So `bb demo` and the test suite start a
;; mock provider first, entirely outside this namespace, and hand in the two
;; facts a resource server actually needs:
;;
;;   :discovery-url   where to find the keys
;;   :issuer          who the tokens must claim to be from
;;
;; If those two strings are all your application knows about your provider,
;; swapping Keycloak for Entra ID is a configuration change. If your provider
;; is in your system map, it is a project.
;; ---------------------------------------------------------------------------

(def default-audience
  "Who tokens must be addressed to: this API, and not the browser app that
  signed the user in. Rejecting an id token is `aud`'s entire job (lab 24)."
  "truck-api")

(def no-provider
  "The issuer a system is given when nobody configured one.

  It is a real string rather than `nil` so that a misconfigured system fails
  the way it should — every token rejected, because none of them claims to
  come from this — instead of failing at startup or, far worse, skipping the
  issuer check. Tests that drive beneath HTTP get this and never notice."
  "urn:lab24:no-identity-provider-configured")

(defn- authentication
  "The three things `adapter/auth.clj` needs, as plain values."
  [{:keys [discovery-url issuer audience verification-keys]}]
  {:verification-keys (or verification-keys (oidc/discovered-keys discovery-url))
   :issuer            (or issuer no-provider)
   :audience          (or audience default-audience)})

(defn in-memory
  "Everything in process. No Docker, no container, no configuration.

  The demo runs on this, and so does most of the test suite — which is only
  possible because the application layer cannot tell the difference."
  ([] (in-memory {}))
  ([{:keys [clock ids oidc]}]
   (let [{:keys [verification-keys issuer audience]} (authentication oidc)
         selected-clock (or clock (clock/system-clock))]
     (component/system-map
      :store             (component/using (memory/store) {:clock :clock})
      :clock             selected-clock
      :ids               (or ids (clock/random-ids))
      ;; `DiscoveredKeys` has a lifecycle (it caches), so Component starts and
      ;; stops it like any other adapter. Nothing about it is special.
      :verification-keys verification-keys
      :issuer            issuer
      :audience          audience))))

(defn with-postgres
  "The same system, one line different.

  One Postgres adapter owns facts, the command ledger and outgoing messages so
  the complete command outcome retains one transaction boundary."
  ([config] (with-postgres config {}))
  ([config {:keys [clock ids oidc]}]
   (let [{:keys [verification-keys issuer audience]} (authentication oidc)]
     (component/system-map
      :database          (postgres/database config)
      :store             (component/using (postgres/store) {:datasource :database})
      :clock             (or clock (clock/system-clock))
      :ids               (or ids (clock/random-ids))
      :verification-keys verification-keys
      :issuer            issuer
      :audience          audience))))

(def ^:private dependency-keys
  [:store :clock :ids :verification-keys :issuer :audience])

(defn- runtime-deps [source]
  (assoc (select-keys source dependency-keys) :outbox (:store source)))

(defrecord HttpServer [port server]
  component/Lifecycle
  (start [this]
    ;; Jetty is required here and nowhere else — `system.clj` is the only file
    ;; allowed to name a concrete adapter, and a web server is an adapter like
    ;; any other. It has a lifecycle, so Component owns it.
    (let [run  (requiring-resolve 'ring.adapter.jetty/run-jetty)
          deps (runtime-deps this)]
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
  (assoc base :http (component/using (map->HttpServer {:port port}) dependency-keys)))

(defn app
  "The application layer's dependencies, taken from a started system.

  `app.clj` receives a plain map — it never sees the system, never calls
  `component/start`, and could not tell you which adapter it was handed. It
  also never touches `:verification-keys`, `:issuer` or `:audience`; those
  travel through it to the HTTP adapter, which is the only thing that
  authenticates."
  [system]
  (runtime-deps system))

(defn start [system] (component/start system))
(defn stop [system] (component/stop system))
