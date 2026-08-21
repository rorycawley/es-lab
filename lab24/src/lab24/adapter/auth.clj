(ns lab24.adapter.auth
  "Authentication: turning a bearer token into a principal, or into nothing.

  Ring middleware, because authentication is a cross-cutting concern of the
  *transport* — it happens to every request the same way, before anything
  domain-shaped is in scope. That makes it adapter work, and it is why nothing
  below `adapter/` in this lab has heard of a token.

  Two rules hold this file together.

  **The principal travels as data.** It is attached to the request map and
  handed onward as an argument. There is no dynamic var, no thread local, no
  `*current-user*`. The moment identity becomes ambient, `decide` stops being
  a function of its inputs, and every reason the core is testable goes with it.

  **A decision is made here, not asked for.** The `VerificationKeys` port
  supplies a key; the judgement of what that key proves is in this file, in
  the open. A port called `authenticate` would have moved the decision to the
  far side of the boundary, where you cannot read it."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [lab24.adapter.wire :as wire]
            [lab24.port.driven :as driven])
  (:import (java.util Base64)))

;; ---------------------------------------------------------------------------
;; `kid` may be read before verification. `alg` may not.
;;
;; Both live in the same unverified header, so the distinction looks arbitrary
;; and is not:
;;
;;   kid  chooses which key to TRY. Choose wrong and verification fails — the
;;        attacker has wasted a lookup.
;;   alg  chooses HOW to verify. Trust it and the attacker picks the algorithm
;;        you check with, which is the whole of CVE-2015-9235 and its family:
;;        say `alg: none` and there is nothing to check, or say `HS256` and a
;;        verifier will happily treat the RSA *public* key as an HMAC secret —
;;        a secret printed in your JWKS.
;;
;; The rule: a value from an unverified token may select an input to
;; verification, never the rule of verification. `:alg :rs256` below is stated
;; by us and read from nowhere. A test forges `alg: none` and asserts it dies.
;; ---------------------------------------------------------------------------

(defn- unverified-header [token]
  (try
    (-> (.decode (Base64/getUrlDecoder) ^String (first (str/split token #"\.")))
        (String. "UTF-8")
        (json/read-str :key-fn keyword))
    (catch Exception _ nil)))

(defn- bearer-token [request]
  (let [header (get-in request [:headers "authorization"])]
    (when (and header (str/starts-with? (str/lower-case header) "bearer "))
      (str/trim (subs header 7)))))

;; ---------------------------------------------------------------------------
;; The principal
;;
;; Lab 1 said it in a warning nothing until now could disobey:
;;
;;   Store an opaque actor id. Never JWTs, tokens or credentials.
;;
;; A token in an append-only store cannot be revoked, drags a bundle of
;; personal claims into the one place designed to resist deletion (lab 15),
;; and proves only that somebody once pasted a string. The `sub` claim is a
;; stable, opaque, meaningless-on-its-own identifier — which is exactly what a
;; fact should carry. A fitness test greps every recorded event for a token.
;; ---------------------------------------------------------------------------

(def ^:private known-roles {"driver" :driver "depot" :depot})

(defn- ->principal [claims]
  (let [subject (:sub claims)]
    (when (and (string? subject) (not (str/blank? subject)))
      {;; The actor is destined for a stream, so both its fields are strings.
       ;; Roles stay process-local keywords, selected from a fixed vocabulary
       ;; rather than interning arbitrary claim values.
       :actor {:type "user" :id subject}
       :roles (into #{} (keep known-roles) (:roles claims))})))

(defn- verify
  "Return a principal or an authentication failure without exposing token
  details. Failure to obtain verification keys is infrastructure failure and
  deliberately propagates rather than being mislabeled as a bad credential."
  [{:keys [verification-keys clock issuer audience]} token]
  (if-let [kid (:kid (unverified-header token))]
    (if-let [key (driven/verification-key verification-keys kid)]
      (try
        (if-let [principal (->principal
                            (jwt/unsign token key {:alg :rs256
                                                   :iss issuer
                                                   :aud audience
                                                   :now (driven/now clock)}))]
          {:principal principal}
          {:failure :invalid-claims})
        (catch clojure.lang.ExceptionInfo e
          ;; buddy reports :exp, :iss, :aud or :signature. Worth keeping apart
          ;; internally — an expired token means *refresh and retry*, and every
          ;; other failure means stop — but the client is told `invalid_token`
          ;; either way, with the reason only in the header where RFC 6750 puts
          ;; it. Whether a signature failed or an audience did is not
          ;; information a caller has earned.
          {:failure (or (:cause (ex-data e)) :invalid)}))
      {:failure :unknown-key})
    {:failure :malformed-token}))

(defn authenticate
  "Middleware that **annotates and never rejects.**

  Splitting it this way is not ceremony. Whether a request carries a valid
  token is a *fact*; whether this endpoint demands one is a *policy*, and they
  belong to different layers — `/health` wants the fact and not the policy."
  [handler deps]
  (fn [request]
    (handler (assoc request :authentication
                    (if-let [token (bearer-token request)]
                      (verify deps token)
                      {:failure :no-token})))))

(defn require-authentication
  "Middleware that **fails closed.**

  The default has to be deny. A gate each handler must remember to call is a
  gate that will be forgotten exactly once, in the endpoint that mattered."
  [handler]
  (fn [request]
    (let [{:keys [principal failure]} (:authentication request)]
      (if principal
        (handler request)
        (wire/respond 401
                      {:error "unauthenticated"}
                      ;; RFC 6750: say *that* it failed in the body, and what
                      ;; the client should do about it in the challenge.
                      {"www-authenticate"
                       (case failure
                         :no-token "Bearer"
                         :exp "Bearer error=\"invalid_token\", error_description=\"token expired\""
                         "Bearer error=\"invalid_token\"")})))))

(defn principal [request] (get-in request [:authentication :principal]))
