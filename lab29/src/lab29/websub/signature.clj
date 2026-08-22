(ns lab29.websub.signature
  "`X-Hub-Signature`, and why a hub signs at all.

  Inside the monolith an inbox trusts the dispatcher, because both are this
  process. A WebSub subscriber has no such luxury: it is on the far side of
  the internet receiving an unsolicited POST, and the only thing distinguishing
  us from anyone else who learned its callback URL is a secret it gave us when
  it subscribed.

  So the signature is computed with **that subscriber's** secret, not one hub
  key. A leaked secret compromises one subscription rather than every
  subscription, which is the same reason lab 28's Stripe adapter verifies with
  a per-endpoint signing secret rather than an API key."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- hmac-sha256 [^String secret ^String body]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
    (->> (.doFinal mac (.getBytes body StandardCharsets/UTF_8))
         (map #(format "%02x" %))
         (apply str))))

(defn sign
  "The header value, in WebSub's `method=signature` form."
  [secret body]
  (str "sha256=" (hmac-sha256 secret body)))

(defn verify
  "For a subscriber to check what it received. Constant-time, for the reason
  lab 28 gave: a short-circuiting comparison leaks how much of a guess was
  right, one byte at a time."
  [secret header body]
  (and (string? header)
       (MessageDigest/isEqual (.getBytes ^String (sign secret body) StandardCharsets/UTF_8)
                              (.getBytes ^String header StandardCharsets/UTF_8))))
