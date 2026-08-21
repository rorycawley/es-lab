(ns lab24.auth-test
  "Authentication, against a real OIDC provider that happens to be a double.

  Every token here was signed by `mock-oauth2-server` with a real RSA key and
  fetched by our own adapter from a real JWKS endpoint over a real socket. The
  forgeries are forged the way forgeries are.

  Where this namespace drives is worth noticing. Most of it enters at
  `auth/authenticate`, which is the middleware itself, because what is under
  test is a *verdict* — accepted, or rejected and why — and the verdict is a
  richer thing than the 401 a client is allowed to see."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab24.adapter.auth :as auth]
            [lab24.adapter.clock :as clock]
            [lab24.adapter.http :as http]
            [lab24.mock-idp :as mock-idp]
            [lab24.system :as system])
  (:import (java.util Base64)))

(defn- with-idp
  "A provider, a system pointed at it, and a clock the test can wind on."
  [opts f]
  (let [idp (mock-idp/start! (select-keys opts [:access-token-seconds]))]
    (try
      (let [held      (clock/held-clock)
            base      {:clock held :oidc (mock-idp/oidc-config idp)}
            overrides (:system opts)
            sys       (system/start
                       (system/in-memory
                        (assoc (merge base overrides)
                               ;; merged, not replaced — a test overriding the
                               ;; issuer still needs the real discovery URL
                               :oidc (merge (:oidc base) (:oidc overrides)))))]
        (try (f {:idp idp :sys sys :clock held :deps (system/app sys)})
             (finally (system/stop sys))))
      (finally (mock-idp/stop! idp)))))

(defn- verdict
  "Run the authentication middleware alone and look at what it concluded."
  [deps token]
  (:authentication
   ((auth/authenticate identity deps)
    (cond-> {:request-method :get :uri "/v1/stock"}
      token (assoc :headers {"authorization" (str "Bearer " token)})))))

(defn- status [deps token]
  (:status ((http/handler deps)
            (cond-> {:request-method :get :uri "/v1/stock"}
              token (assoc :headers {"authorization" (str "Bearer " token)})))))

;; ---------------------------------------------------------------------------
;; What a login produces
;; ---------------------------------------------------------------------------

(deftest a-login-yields-three-tokens-test
  (with-idp {}
    (fn [{:keys [idp]}]
      (let [tokens (mock-idp/login idp :dana)]
        (is (some? (:access_token tokens))  "what the bearer may do")
        (is (some? (:id_token tokens))      "who signed in")
        (is (some? (:refresh_token tokens)) "the right to ask for another")
        (is (= "Bearer" (:token_type tokens)))
        (is (pos? (:expires_in tokens)))

        (testing "and the two JWTs are not interchangeable"
          (is (not= (:access_token tokens) (:id_token tokens))))

        (testing "the refresh token is opaque, not a JWT"
          ;; It is a handle, meaningless without the provider — which is
          ;; exactly what makes it revocable, and what an access token can
          ;; never be.
          (is (not (str/includes? (:refresh_token tokens) "."))))))))

(deftest a-valid-token-becomes-a-principal-test
  (with-idp {}
    (fn [{:keys [idp deps]}]
      (let [{:keys [principal]} (verdict deps (:access_token (mock-idp/login idp :dana)))]
        (is (= {:type "user" :id "USR-83721"} (:actor principal)))
        (is (= #{:driver} (:roles principal)))
        (testing "and the principal carries nothing else"
          (is (= #{:actor :roles} (set (keys principal)))
              "no token, no claims, no expiry — the shell keeps what it needs"))))))

;; ---------------------------------------------------------------------------
;; Ways a token fails
;; ---------------------------------------------------------------------------

(deftest no-token-is-not-a-bad-token-test
  (with-idp {}
    (fn [{:keys [deps]}]
      (is (= :no-token (:failure (verdict deps nil))))
      (is (= 401 (status deps nil)))
      (testing "and health does not ask"
        (is (= 200 (:status ((http/handler deps) {:request-method :get :uri "/health"}))))))))

(deftest a-token-that-is-not-a-token-test
  (with-idp {}
    (fn [{:keys [deps]}]
      (is (= :malformed-token (:failure (verdict deps "not-a-jwt"))))
      (is (= 401 (status deps "not-a-jwt"))))))

(deftest an-expired-token-is-rejected-without-waiting-test
  (with-idp {:access-token-seconds 300}
    (fn [{:keys [idp deps clock]}]
      (let [token (:access_token (mock-idp/login idp :dana))]
        (is (some? (:principal (verdict deps token))) "valid now")

        ;; Lab 21 made `now` a port. This is the invoice being settled: five
        ;; minutes of validity, tested in no time at all.
        (clock/advance! clock 600)

        (is (= :exp (:failure (verdict deps token))) "and not valid ten minutes on")
        (is (= 401 (status deps token)))

        (testing "and the challenge tells the client it was expiry, per RFC 6750"
          (let [response ((http/handler deps)
                          {:request-method :get :uri "/v1/stock"
                           :headers {"authorization" (str "Bearer " token)}})]
            (is (str/includes? (get-in response [:headers "www-authenticate"]) "expired"))))))))

(deftest a-token-addressed-to-someone-else-is-rejected-test
  (with-idp {:system {:oidc {:audience "some-other-api"}}}
    (fn [{:keys [idp deps]}]
      ;; A flawless token — right issuer, right key, unexpired, real user —
      ;; and not addressed to us. This is the check that keeps an **id token**
      ;; out of an API: an id token is addressed to the *client*, so a browser
      ;; app can render "Hello, Dana", and sending it to a resource server is
      ;; the classic OIDC mistake. `aud` is the only thing that stops it.
      (is (= :aud (:failure (verdict deps (:access_token (mock-idp/login idp :dana))))))
      (is (= 401 (status deps (:access_token (mock-idp/login idp :dana))))))))

(deftest the-double-gives-both-tokens-the-same-claims-test
  (with-idp {}
    (fn [{:keys [idp deps]}]
      (let [tokens (mock-idp/login idp :dana)]
        ;; Recorded rather than hidden. This provider applies one set of claims
        ;; to everything it signs, so its id token carries `aud: truck-api` and
        ;; our API accepts it — correctly, on the claims it was given.
        ;;
        ;; Against a real provider the id token would carry the client's
        ;; audience and the test above would catch it. **So the mistake this
        ;; lab warns about is exactly the one the double cannot reproduce**,
        ;; which is what "a mock proves your verification logic, not your
        ;; integration" means when it stops being a slogan.
        ;;
        ;; If this assertion ever fails, the double got better and the note
        ;; above is stale.
        (is (some? (:principal (verdict deps (:id_token tokens))))
            "accepted here, and it would not be in production")
        (is (not= (:access_token tokens) (:id_token tokens))
            "still two different tokens, with different jti and different purpose")))))

(deftest a-token-from-a-different-provider-is-rejected-test
  (let [other (mock-idp/start!)]
    (try
      (with-idp {}
        (fn [{:keys [deps]}]
          (let [foreign (:access_token (mock-idp/login other :dana))]
            ;; Rejected on the issuer, which is the *first* thing checked and
            ;; the right first thing: it is one string comparison, and it
            ;; settles the question before any key is trusted. The signature
            ;; would have failed too — the key ids collide, so the wrong key
            ;; gets tried — but that check never has to run.
            (is (= :iss (:failure (verdict deps foreign))))
            (is (= 401 (status deps foreign))))))
      (finally (mock-idp/stop! other)))))

(deftest the-issuer-must-be-the-one-we-expect-test
  (with-idp {:system {:oidc {:issuer "https://not-our-provider.test"}}}
    (fn [{:keys [idp deps]}]
      ;; The system was told to expect a different issuer, so a perfectly good
      ;; token from the real one must fail. Without this check, any provider
      ;; whose keys you happen to fetch can mint principals in your system.
      (is (= :iss (:failure (verdict deps (:access_token (mock-idp/login idp :dana)))))))))

;; ---------------------------------------------------------------------------
;; The forgery the library exists to stop
;; ---------------------------------------------------------------------------

(defn- b64url [s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes ^String s "UTF-8")))

(deftest alg-none-is-not-an-algorithm-test
  (with-idp {}
    (fn [{:keys [deps]}]
      (let [forged (str (b64url (json/write-str {:alg "none" :kid "truck" :typ "JWT"}))
                        "."
                        (b64url (json/write-str {:sub "USR-83721" :roles ["depot"]
                                                 :aud "truck-api"}))
                        ".")]
        (testing "an unsigned token asserting whatever it likes"
          (is (= :signature (:failure (verdict deps forged))))
          (is (= 401 (status deps forged))))

        (testing "it fails because the verifier states the algorithm itself"
          ;; `{:alg :rs256}` in adapter/auth.clj is written by us and read from
          ;; nowhere. Take it from the token's header instead — which is what
          ;; CVE-2015-9235 and its relatives are — and this forgery is a valid
          ;; token signed by nobody.
          (is (str/includes? (slurp "src/lab24/adapter/auth.clj") ":alg :rs256")))))))

;; ---------------------------------------------------------------------------
;; Keys are fetched, cached, and refetched — because providers rotate
;; ---------------------------------------------------------------------------

(deftest keys-are-fetched-once-and-cached-test
  (with-idp {}
    (fn [{:keys [idp sys deps]}]
      (let [fetches (fn [] (:refetched @(:state (:verification-keys sys))))]
        (is (zero? (fetches)) "nothing is fetched at startup — a provider that is
                               briefly down must not stop the app from starting")
        (verdict deps (:access_token (mock-idp/login idp :dana)))
        (is (= 1 (fetches)))
        (verdict deps (:access_token (mock-idp/login idp :sam)))
        (is (= 1 (fetches)) "the second token needs no second fetch")

        (testing "an unknown kid does trigger one, which is what rotation looks like"
          (let [[_ payload signature] (str/split (:access_token (mock-idp/login idp :dana)) #"\.")
                relabelled (str (b64url (json/write-str {:alg "RS256" :kid "rotated-key"}))
                                "." payload "." signature)]
            (is (= :unknown-key (:failure (verdict deps relabelled))))
            (is (= 2 (fetches))
                "the adapter went back to the provider rather than assuming")))))))

;; ---------------------------------------------------------------------------
;; The refresh loop, which is the only test here that waits
;; ---------------------------------------------------------------------------

(deftest an-expired-token-is-recovered-by-refreshing-test
  (with-idp {:access-token-seconds 2 :system {:clock nil}}
    (fn [{:keys [idp deps]}]
      (let [tokens (mock-idp/login idp :dana)]
        (is (= 200 (status deps (:access_token tokens))))

        ;; Everything else in this namespace winds a held clock instead. This
        ;; one sleeps on purpose: the held clock proves *our verifier* honours
        ;; `exp`, and only real time proves the *provider* issues a token that
        ;; actually stops working.
        (Thread/sleep 2500)
        (is (= 401 (status deps (:access_token tokens))))

        (let [refreshed (mock-idp/refresh idp :dana (:refresh_token tokens))]
          (is (not= (:access_token tokens) (:access_token refreshed))
              "a new access token, without the user signing in again")
          (is (= 200 (status deps (:access_token refreshed)))))))))
