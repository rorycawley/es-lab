(ns lab24.adapter.oidc
  "The identity provider, as a **driven** adapter.

  This is the half of authentication people forget exists. A token is pushed
  at you through a driving adapter; the key that proves it has to be *fetched*,
  by you, from the provider. Same external system, opposite arrow — which
  makes OIDC the clearest illustration in this repository of what driving and
  driven actually mean. It is not a property of HTTP, or of who owns the
  server. It is the direction of the call.

  What a resource server has to do, and all it has to do:

    1. read the discovery document, once, to find `jwks_uri`
    2. fetch the keys
    3. look one up by the `kid` in the token's header
    4. refetch when a `kid` is unknown, because that is key rotation

  Step 4 is the one that gets skipped, and it fails months later on the day
  the provider rotates — which is why `refetched` is counted below and a test
  asserts it happens."
  (:require [clojure.data.json :as json]
            [com.stuartsierra.component :as component]
            [buddy.core.keys :as buddy-keys]
            [lab24.port.driven :as driven])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- fetch-json [url]
  (-> (HttpClient/newHttpClient)
      (.send (-> (HttpRequest/newBuilder (URI/create (str url))) (.GET) (.build))
             (HttpResponse$BodyHandlers/ofString))
      (.body)
      (json/read-str :key-fn keyword)))

(defn- fetch-keys
  "Every published key, as {kid -> PublicKey}.

  A JWKS is a *set* on purpose. During a rotation it holds the new key and the
  old one at the same time, because tokens signed with the old one are still
  in flight and still valid until they expire."
  [jwks-uri]
  (into {}
        (map (fn [jwk] [(:kid jwk) (buddy-keys/jwk->public-key jwk)]))
        (:keys (fetch-json jwks-uri))))

(defrecord DiscoveredKeys [discovery-url state]
  component/Lifecycle
  (start [this]
    ;; Deliberately not fetched here. A provider that is briefly down must not
    ;; stop your application from starting — it must stop it from *verifying*,
    ;; and only until the provider comes back.
    (assoc this :state (atom {:keys {} :refetched 0})))
  (stop [this] (assoc this :state nil))

  driven/VerificationKeys
  (verification-key [_ kid]
    (or (get-in @state [:keys kid])
        (let [jwks-uri (:jwks_uri (fetch-json discovery-url))
              fetched  (fetch-keys jwks-uri)]
          (swap! state (fn [s] (-> s (update :keys merge fetched) (update :refetched inc))))
          (get fetched kid)))))

(defn discovered-keys
  "Keys fetched from a running provider over HTTP.

  In production this wants a timeout, a retry, a bounded cache and a floor on
  how often an unknown `kid` may trigger a refetch — otherwise a forged token
  carrying a random `kid` is a request amplifier pointed at your provider.
  Left out here so the four steps stay readable, and named so the omission is
  not mistaken for a recommendation."
  [discovery-url]
  (map->DiscoveredKeys {:discovery-url discovery-url}))

(defrecord StaticKeys [by-kid]
  driven/VerificationKeys
  (verification-key [_ kid] (get by-kid kid)))

(defn static-keys
  "Keys handed over directly, for a test that has no reason to involve a
  socket. The second implementation — without one, `VerificationKeys` would be
  indirection rather than a boundary (lab 21)."
  [by-kid]
  (->StaticKeys by-kid))
