(ns lab24.mock-idp
  "An identity provider, borrowed rather than written.

  `no.nav.security/mock-oauth2-server` is a real OIDC provider that happens to
  be a test double: discovery document, JWKS, authorization endpoint, token
  endpoint, RSA signing, the lot. Everything in this namespace is *driving*
  it — configuration and a client. There is no protocol implementation here,
  because writing one would have been the wrong exercise. **You never write an
  identity provider. You integrate with one.**

  ## Why this lives in `dev/` and not in `src/`

  Because an identity provider is not a dependency of your application. It is
  a dependency of your *tests*. `dev` is on the classpath for `bb demo`,
  `bb serve` and `bb test`, and for nothing else — a fitness test asserts that
  nothing under `src/` so much as names this library.

  ## Where the double stops resembling the real thing

  Two places, and naming them is the point of a lab rather than a demo.

  **It picks a persona by `client_id`.** Its rules can only match on request
  parameters, and the only one that survives an authorization-code exchange is
  the client. So each persona gets its own client here. A real provider picks
  the subject by *who signed in*, and `client_id` identifies the application.
  Nothing downstream can tell — the tokens are shaped identically — but the
  login step below is not what a login looks like.

  **It does not rotate refresh tokens.** Refreshing returns the same refresh
  token, and presenting a retired one succeeds. RFC 9700 §4.14.2 requires the
  opposite: rotate on every use, and if a retired token comes back, revoke the
  entire family, because two parties holding one chain has no innocent
  explanation. That behaviour is a *provider's* to implement and this one does
  not, so this lab does not claim to demonstrate it.

  Which is the caveat from the README in concrete form: **a mock proves your
  verification logic, not your integration.** Everything under `src/` is
  exercised honestly here. Nothing here proves you could log in to Keycloak."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (no.nav.security.mock.oauth2 MockOAuth2Server OAuth2Config)))

(def issuer-id "truck")
(def audience "truck-api")
(def redirect-uri "http://localhost:3000/callback")

(def personas
  "Three people, and between them every case the lab needs: a driver assigned
  to the truck, a driver who is not, and somebody from the depot."
  {:dana {:client-id "truck-till-dana" :sub "USR-83721" :roles ["driver"]
          :label "Dana, driving this truck"}
   :sam  {:client-id "truck-till-sam"  :sub "USR-55010" :roles ["driver"]
          :label "Sam, driving a different truck"}
   :rudi {:client-id "truck-depot"     :sub "USR-11902" :roles ["depot"]
          :label "Rudi, at the depot"}})

(defn- config-json
  "The whole provider, as data. Worth pausing on: the identity provider is
  *configured*, not programmed, and this is the entire configuration."
  [token-expiry-seconds]
  (json/write-str
   {:interactiveLogin false
    :tokenCallbacks
    [{:issuerId        issuer-id
      :tokenExpiry     token-expiry-seconds
      :requestMappings (mapv (fn [{:keys [client-id sub roles]}]
                               {:requestParam "client_id"
                                :match        client-id
                                :claims       {:sub   sub
                                               :aud   [audience]
                                               :roles roles}})
                             (vals personas))}]}))

;; ---------------------------------------------------------------------------
;; A tiny OAuth client, on the JDK's HTTP client
;; ---------------------------------------------------------------------------

(def ^:private client
  ;; Redirects must NOT be followed: the authorization code arrives *in* the
  ;; redirect, and a client that follows it throws away what it came for.
  (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build)))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- GET [url]
  (let [response (.send client
                        (-> (HttpRequest/newBuilder (URI/create (str url))) (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status   (.statusCode response)
     :location (-> response .headers (.firstValue "location") (.orElse nil))}))

(defn- form-post [url params]
  (let [body     (str/join "&" (map (fn [[k v]] (str k "=" (encode v))) params))
        response (.send client
                        (-> (HttpRequest/newBuilder (URI/create (str url)))
                            (.header "content-type" "application/x-www-form-urlencoded")
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body   (json/read-str (.body response) :key-fn keyword)}))

;; ---------------------------------------------------------------------------

(defn start!
  "Start a provider on a random port. Returns a handle, not a component —
  see `system.clj` for why it is not in the system map."
  ([] (start! {}))
  ([{:keys [access-token-seconds] :or {access-token-seconds 120}}]
   (let [server (doto (MockOAuth2Server.
                       (.fromJson OAuth2Config/Companion (config-json access-token-seconds)))
                  (.start))]
     {:server               server
      :issuer               (str (.issuerUrl server issuer-id))
      :discovery-url        (str (.wellKnownUrl server issuer-id))
      :jwks-url             (str (.jwksUrl server issuer-id))
      :token-endpoint       (str (.tokenEndpointUrl server issuer-id))
      :authorize-endpoint   (str (.authorizationEndpointUrl server issuer-id))
      :access-token-seconds access-token-seconds})))

(defn stop! [idp] (.shutdown ^MockOAuth2Server (:server idp)))

(defn oidc-config
  "The two strings your application is allowed to know about your provider."
  [idp]
  {:discovery-url (:discovery-url idp)
   :issuer        (:issuer idp)
   :audience      audience})

(defn login
  "The authorization-code flow, in the two steps it actually is.

  1. `GET /authorize` → a 302 whose `Location` carries a one-use code
  2. `POST /token`    → access_token, id_token, refresh_token

  A real client adds PKCE — a `code_challenge` on the way in and the
  `code_verifier` on the way back — so that intercepting the code is not
  enough to redeem it. Omitted here because it is a property of the *client*,
  and this lab is about the resource server."
  [idp persona-key]
  (let [{:keys [client-id]} (get personas persona-key)
        authorize (GET (str (:authorize-endpoint idp)
                            "?response_type=code"
                            "&client_id=" (encode client-id)
                            "&redirect_uri=" (encode redirect-uri)
                            "&scope=" (encode "openid")
                            "&state=" (encode (str (random-uuid)))))
        code      (second (re-find #"code=([^&]+)" (str (:location authorize))))]
    (:body (form-post (:token-endpoint idp)
                      {"grant_type"    "authorization_code"
                       "code"          code
                       "redirect_uri"  redirect-uri
                       "client_id"     client-id
                       "client_secret" "s3cret"}))))

(defn refresh
  "Trade a refresh token for a fresh access token, without the user again.

  This is the whole reason access tokens may be short-lived: a client can
  recover from an expiry on its own, so a five-minute token costs nobody a
  login."
  [idp persona-key refresh-token]
  (let [{:keys [client-id]} (get personas persona-key)]
    (:body (form-post (:token-endpoint idp)
                      {"grant_type"    "refresh_token"
                       "refresh_token" refresh-token
                       "client_id"     client-id
                       "client_secret" "s3cret"}))))

(defn bearer [tokens] (str "Bearer " (:access_token tokens)))
