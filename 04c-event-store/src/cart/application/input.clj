(ns cart.application.input
  "Shared normalization for declared application input values."
  (:refer-clojure :exclude [parse-uuid])
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

(defn parse-uuid
  "Parses canonical UUID text while treating letter case as representation only."
  [value]
  (try
    (when (string? value)
      (let [parsed (UUID/fromString value)]
        (when (= (str parsed) (str/lower-case value))
          parsed)))
    (catch IllegalArgumentException _ nil)))
