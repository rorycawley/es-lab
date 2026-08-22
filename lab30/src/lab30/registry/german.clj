(ns lab30.registry.german
  "A small, explicit fallback for the one language feature PostgreSQL's stock
  image cannot supply.

  Snowball stems German words but does not split compounds. PostgreSQL can use
  Hunspell dictionaries for basic compound splitting, but it ships no Ispell
  files and the host package becomes part of the index version. This lab keeps
  that dependency visible as a versioned word list and a pure function.

  A production register needs a jurisdiction-specific lexicon built from its
  corpus. This deliberately tiny list exists to make the design and rebuild
  path executable, not to claim linguistic completeness."
  (:require [clojure.string :as str])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util Locale)))

(def version 1)

(def ^:private lexicon
  #{"bäckerei" "gesellschaft" "kraft" "müller" "söhne" "verwaltung"
    "vermögen" "wasser"})

(def ^:private linkers ["" "s" "es" "n" "en" "er" "e"])

(defn- fold [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFC)
      (.toLowerCase Locale/ROOT)))

(defn- segment
  "Return one complete lexicon segmentation of `word`, including German
  linking letters, or nil. Prefer more component words when several parses
  are possible."
  [word]
  (let [words (sort-by (juxt (comp - count) identity) lexicon)
        size  (count word)
        memo  (atom {})]
    (letfn [(walk [at]
              (if (= at size)
                [[]]
                (if (contains? @memo at)
                  (get @memo at)
                  (let [answers
                        (vec
                         (for [part words
                               :when (str/starts-with? (subs word at) part)
                               :let [after-part (+ at (count part))]
                               linker linkers
                               :when (and (<= (+ after-part (count linker)) size)
                                          (str/starts-with? (subs word after-part) linker))
                               tail (walk (+ after-part (count linker)))]
                           (cons part tail)))]
                    (swap! memo assoc at answers)
                    answers))))]
      (->> (walk 0)
           (filter #(> (count %) 1))
           (sort-by (juxt (comp - count) #(str/join " " %)))
           first))))

(defn parts
  "Space-separated component words derived from a filed name."
  [filed-name]
  (->> (str/split (fold filed-name) #"[^\p{L}\p{N}]+")
       (keep segment)
       (mapcat identity)
       distinct
       (str/join " ")))
