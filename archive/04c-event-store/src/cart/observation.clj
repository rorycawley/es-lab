(ns cart.observation
  "Versioned HMAC observations bound to a cart and stream revision."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64 UUID]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private encoder (.withoutPadding (Base64/getUrlEncoder)))
(def ^:private decoder (Base64/getUrlDecoder))
(def ^:private utf8 StandardCharsets/UTF_8)

(defn- utf8-bytes [value]
  (.getBytes ^String value utf8))

(defn- b64-encode [value]
  (.encodeToString encoder value))

(defn- b64-decode [value]
  (.decode decoder value))

(defn- sign [secret message]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (utf8-bytes secret) "HmacSHA256")))]
    (.doFinal mac (utf8-bytes message))))

(defn validate-key-ring!
  [{:keys [active-key-id keys] :as key-ring}]
  (when-not (and (string? active-key-id)
                 (re-matches #"[A-Za-z0-9_-]+" active-key-id)
                 (map? keys)
                 (seq keys)
                 (every? (fn [[key-id secret]]
                           (and (string? key-id)
                                (re-matches #"[A-Za-z0-9_-]+" key-id)
                                (string? secret)
                                (not (str/blank? secret))))
                         keys)
                 (contains? keys active-key-id))
    (throw (ex-info "Invalid observation signing key ring" {})))
  key-ring)

(defn issue
  "Issues a version 1 marker. The key ring is supplied by the imperative shell."
  [key-ring cart-id revision]
  (validate-key-ring! key-ring)
  (when-not (and (instance? UUID cart-id) (pos-int? revision))
    (throw (ex-info "Observation requires a cart UUID and positive revision"
                    {:cart-id cart-id :revision revision})))
  (let [key-id  (:active-key-id key-ring)
        payload (str cart-id ":" revision)
        encoded (b64-encode (utf8-bytes payload))
        signed  (str "v1." key-id "." encoded)]
    (str signed "." (b64-encode (sign (get-in key-ring [:keys key-id]) signed)))))

(defn verify
  "Returns represented cart/revision or an invalid marker result."
  [key-ring marker]
  (try
    (validate-key-ring! key-ring)
    (let [[version key-id encoded signature :as parts]
          (when (string? marker) (str/split marker #"\." -1))]
      (cond
        (not= 4 (count parts)) {:error :invalid-cart-observation}
        (not= "v1" version) {:error :invalid-cart-observation}
        (not (contains? (:keys key-ring) key-id)) {:error :invalid-cart-observation}
        :else
        (let [signed   (str version "." key-id "." encoded)
              expected (sign (get-in key-ring [:keys key-id]) signed)
              actual   (b64-decode signature)]
          (if-not (MessageDigest/isEqual expected actual)
            {:error :invalid-cart-observation}
            (let [[cart-id revision & extra]
                  (str/split (String. (b64-decode encoded) utf8) #":" -1)
                  parsed-revision (parse-long revision)]
              (if (or (seq extra) (nil? parsed-revision) (not (pos? parsed-revision)))
                {:error :invalid-cart-observation}
                {:ok {:cart-id (UUID/fromString cart-id)
                      :revision parsed-revision}}))))))
    (catch Exception _
      {:error :invalid-cart-observation})))

(defn same-observation?
  "Compares semantic observations, never marker bytes."
  [left right]
  (= left right))
