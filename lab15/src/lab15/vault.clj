(ns lab15.vault
  "A value model of per-subject keys, and the encryption they protect.

  AES-256-GCM is supplied by the JDK. The envelope format and key lifecycle
  are application design, not a production cryptographic system. What matters
  here is one non-replaceable key per subject and an after-state in which that
  key is unavailable.

  A real implementation belongs behind a KMS or secrets-manager adapter with
  access control, audit, rotation, backup and verified-destruction policies."
  (:require [clojure.edn :as edn])
  (:import (java.nio.charset StandardCharsets)
           (java.security SecureRandom)
           (java.util Base64)
           (javax.crypto Cipher KeyGenerator SecretKey)
           (javax.crypto.spec GCMParameterSpec)))

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)
(def ^:private envelope-version 1)
(def ^:private algorithm "AES-256-GCM")
(def ^:private secure-random (SecureRandom.))
(def ^:private destroyed ::destroyed)

(defn- encode ^String [^bytes bs] (.encodeToString (Base64/getEncoder) bs))
(defn- decode ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn generate-key
  "A fresh AES-256 key, for one data subject."
  ^SecretKey []
  (let [generator (KeyGenerator/getInstance "AES")]
    (.init generator 256)
    (.generateKey generator)))

(defn personal-context
  "Stable additional authenticated data for the one sealed field in the lab."
  [subject-id event-id]
  (str "card-issued/personal/" subject-id "/" event-id))

(defn seal
  "Encrypt `value` under `key` and bind it to `context`.

  The versioned envelope supports fail-closed format evolution. The context is
  authenticated but not encrypted, so moving ciphertext to another subject or
  purpose fails authentication."
  [^SecretKey key context value]
  (let [iv     (byte-array iv-bytes)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.nextBytes secure-random iv)
    (.init cipher Cipher/ENCRYPT_MODE key (GCMParameterSpec. gcm-tag-bits iv))
    (.updateAAD cipher (.getBytes ^String context StandardCharsets/UTF_8))
    {:crypto/version envelope-version
     :algorithm      algorithm
     :iv             (encode iv)
     :ciphertext (encode (.doFinal cipher (.getBytes (pr-str value) "UTF-8")))}))

(defn validate-sealed
  "Reject an envelope whose format this reader does not support."
  [{:keys [crypto/version algorithm iv ciphertext] :as sealed}]
  (when-not (= envelope-version version)
    (throw (ex-info "Unsupported sealed-value version"
                    {:crypto/version version})))
  (when-not (= lab15.vault/algorithm algorithm)
    (throw (ex-info "Unsupported sealed-value algorithm"
                    {:algorithm algorithm})))
  (when-not (and (string? iv) (string? ciphertext))
    (throw (ex-info "Malformed sealed value"
                    {:sealed sealed})))
  sealed)

(defn unseal
  "Decrypt a supported envelope in `context`.

  Missing keys are handled by the read edge. A wrong key, wrong context,
  malformed envelope or unsupported version remains an error; none of those
  conditions proves erasure."
  [^SecretKey key context sealed]
  (let [{:keys [iv ciphertext]} (validate-sealed sealed)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/DECRYPT_MODE key (GCMParameterSpec. gcm-tag-bits (decode iv)))
    (.updateAAD cipher (.getBytes ^String context StandardCharsets/UTF_8))
    (edn/read-string (String. (.doFinal cipher (decode ciphertext)) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; The key store, as a value.
;;
;; Real ones are a KMS or a secrets manager. What matters for the lab is that
;; it supports the destructive key-lifecycle operation this event log does not.
;; ---------------------------------------------------------------------------

(def empty-vault {})

(defn hold
  [vault subject-id key]
  (when (contains? vault subject-id)
    (throw (ex-info "Subject key already exists or was destroyed"
                    {:subject-id subject-id})))
  (when-not (instance? SecretKey key)
    (throw (ex-info "Invalid subject key"
                    {:subject-id subject-id})))
  (when (some #(= key %) (vals vault))
    (throw (ex-info "Key already belongs to another subject"
                    {:subject-id subject-id})))
  (assoc vault subject-id key))

(defn key-for
  [vault subject-id]
  (let [entry (get vault subject-id)]
    (when-not (= destroyed entry) entry)))

(defn destroy
  "Return the vault state after the subject key is made unavailable.

  A tombstone prevents accidental subject-id reuse with a different key. This
  pure value does not sanitize JVM memory or any retained copy of an earlier
  vault value. A real adapter must destroy every recoverable key copy and
  verify that operation."
  [vault subject-id]
  (if (contains? vault subject-id)
    (assoc vault subject-id destroyed)
    vault))
