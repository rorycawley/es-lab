(ns lab15.vault
  "Per-subject keys, and the encryption they protect.

  This is the smallest interesting part of the lab and the least of its
  lesson. The cryptography is the JDK's — AES-256 in GCM, standard library,
  no invention. What matters is the **key lifecycle**: one key per data
  subject, held somewhere you can delete from, and destroying it is what
  erasure means here.

  Note what that admits. Crypto-shredding does not remove the need for a
  mutable, deletable store; it shrinks it to one small enough to reason
  about. The log stays append-only because the vault does not."
  (:require [clojure.edn :as edn])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (javax.crypto Cipher KeyGenerator SecretKey)
           (javax.crypto.spec GCMParameterSpec)))

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)

(defn- encode ^String [^bytes bs] (.encodeToString (Base64/getEncoder) bs))
(defn- decode ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn generate-key
  "A fresh AES-256 key, for one data subject."
  ^SecretKey []
  (let [generator (KeyGenerator/getInstance "AES")]
    (.init generator 256)
    (.generateKey generator)))

(defn seal
  "Encrypt `value` under `key`, returning something safe to append forever."
  [^SecretKey key value]
  (let [iv     (byte-array iv-bytes)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.nextBytes (SecureRandom.) iv)
    (.init cipher Cipher/ENCRYPT_MODE key (GCMParameterSpec. gcm-tag-bits iv))
    {:iv         (encode iv)
     :ciphertext (encode (.doFinal cipher (.getBytes (pr-str value) "UTF-8")))}))

(defn unseal
  "Decrypt what `seal` produced. Throws if the key is wrong."
  [^SecretKey key {:keys [iv ciphertext]}]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/DECRYPT_MODE key (GCMParameterSpec. gcm-tag-bits (decode iv)))
    (edn/read-string (String. (.doFinal cipher (decode ciphertext)) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; The key store, as a value.
;;
;; Real ones are a KMS or a secrets manager. What matters for the lab is that
;; it supports the one operation the event log must never support: removal.
;; ---------------------------------------------------------------------------

(def empty-vault {})

(defn hold
  [vault subject-id key]
  (assoc vault subject-id key))

(defn key-for
  [vault subject-id]
  (get vault subject-id))

(defn destroy
  "Erasure. Everything sealed under this subject's key becomes unreadable, in
  the log, in every backup of the log, and everywhere it was ever copied — at
  once, without touching any of them.

  That is the whole appeal, and the reason it is worth the key management."
  [vault subject-id]
  (dissoc vault subject-id))
