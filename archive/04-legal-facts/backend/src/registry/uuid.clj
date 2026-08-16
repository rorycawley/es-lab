(ns registry.uuid
  (:import [com.github.f4b6a3.uuid UuidCreator]))

(defn v7 ^java.util.UUID []
  (UuidCreator/getTimeOrderedEpoch))

(defn v7-str [] (str (v7)))
(defn parse [s] (java.util.UUID/fromString s))
(defn timestamp-ms [uuid] (bit-shift-right (.getMostSignificantBits uuid) 16))
