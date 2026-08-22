(ns lab30.registry.search-entities
  "The complete `Search registered entities` query slice.

  This is a cascade, not one clever score. Cheap and precise identifiers run
  first. Linguistic and fuzzy work is paid for only when those rungs return
  nothing, and the response names which promise produced the result."
  (:require [clojure.string :as str]
            [lab30.registry.legal-form :as legal-form]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.text Normalizer Normalizer$Form)))

(def Request
  [:map {:closed true}
   [:query [:string {:min 1 :max 300}]]
   [:ui-language [:enum :fr :de :it]]
   [:limit {:optional true} [:int {:min 1 :max 50}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(def ^:private default-limit 10)

(defn prepare
  "Make user text representable before it reaches JDBC. NFC is canonical
  encoding, not a search approximation; the database still owns case,
  accent, punctuation, and language analysis."
  [request]
  (update request :query
          #(-> (str %)
               (str/replace "\u0000" "")
               (Normalizer/normalize Normalizer$Form/NFC)
               str/trim)))

(def ^:private collations
  {:fr "\"fr-x-icu\"" :de "\"de-x-icu\"" :it "\"it-x-icu\""})

(def ^:private columns
  "entity_id, reg_no AS registration_number, euid, name,
   legal_form::text AS legal_form, filing_lang::text AS filing_language")

(defn- ordered-sql [head ui-language]
  (str head " ORDER BY name COLLATE " (collations ui-language) " LIMIT ?"))

(defn- identifier-sql [ui-language]
  (ordered-sql
   (str "SELECT " columns " FROM registry.entity
          WHERE (reg_no = ? OR euid = ?) AND removed_at IS NULL")
   ui-language))

(defn- exact-name-sql [ui-language]
  (ordered-sql
   (str "SELECT " columns " FROM registry.entity
          WHERE name_ci = casefold(normalize(?::text, NFC))
            AND removed_at IS NULL")
   ui-language))

(defn- prefix-sql [ui-language]
  (ordered-sql
   (str "SELECT " columns " FROM registry.entity
          WHERE name_ci LIKE casefold(normalize(?::text, NFC)) || '%'
            AND removed_at IS NULL")
   ui-language))

(defn- phrase-sql [ui-language]
  (str "WITH query AS
          (SELECT websearch_to_tsquery('registry.fr'::regconfig, ?) ||
                  websearch_to_tsquery('registry.de'::regconfig, ?) ||
                  websearch_to_tsquery('registry.it'::regconfig, ?) AS q)
        SELECT " columns ", ts_rank_cd(name_tsv, q, 32) AS rank
          FROM registry.entity, query
         WHERE name_tsv @@ q AND removed_at IS NULL
         ORDER BY rank DESC, name COLLATE " (collations ui-language) " LIMIT ?"))

(defn- fuzzy-sql [ui-language]
  (str "SELECT " columns ",
               strict_word_similarity(registry.search_key(?), name_key) AS similarity
          FROM registry.entity
         WHERE registry.search_key(?) <<% name_key
           AND removed_at IS NULL
         ORDER BY similarity DESC, name COLLATE " (collations ui-language) " LIMIT ?"))

(defn- rows [datasource sql params]
  (vec (jdbc/execute! datasource (into [sql] params) opts)))

(defn- decorate [ui-language row]
  (let [form (keyword (:legal-form row))]
    (-> row
        (assoc :legal-form form
               :filing-language (keyword (:filing-language row))
               :display-name (legal-form/display-name (:name row) form ui-language)))))

(defn- answer [rung query ui-language rows]
  (when (seq rows)
    {:rung rung
     :query query
     :found (mapv #(decorate ui-language %) rows)}))

(defn handle
  [{:keys [datasource]} {:keys [query ui-language limit]}]
  (let [limit      (or limit default-limit)
        name-query (legal-form/strip-query-suffix query)
        key-length (when-not (str/blank? name-query)
                     (:length
                      (jdbc/execute-one!
                       datasource
                       ["SELECT char_length(registry.search_key(?)) AS length" name-query]
                       opts)))]
    (or
     (answer :registration query ui-language
             (rows datasource (identifier-sql ui-language) [query query limit]))
     (when-not (str/blank? name-query)
       (answer :exact-name query ui-language
               (rows datasource (exact-name-sql ui-language) [name-query limit])))
     (when-not (str/blank? name-query)
       (answer :prefix query ui-language
               (rows datasource (prefix-sql ui-language) [name-query limit])))
     (when-not (str/blank? name-query)
       (answer :phrase query ui-language
               (rows datasource (phrase-sql ui-language)
                     [name-query name-query name-query limit])))
     (when (<= 3 (or key-length 0))
       (answer :fuzzy query ui-language
               (rows datasource (fuzzy-sql ui-language)
                     [name-query name-query limit])))
     {:no-matches query})))
