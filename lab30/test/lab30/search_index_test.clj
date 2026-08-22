(ns lab30.search-index-test
  "The four keys have separate jobs and every derived one is rebuildable."
  (:require [clojure.test :refer [deftest is]]
            [lab30.fixture :as fixture]
            [lab30.postgres :as postgres]
            [lab30.registry.api :as registry]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(deftest one-legal-name-has-three-different-derived-search-jobs-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "L’Oréal Société"
                         {:filing-language :fr :legal-form :plc})
      (let [row (jdbc/execute-one!
                 (postgres/datasource)
                 ["SELECT name, name_ci, name_key, name_tsv::text AS name_tsv,
                          legal_form::text AS legal_form
                     FROM registry.entity"]
                 opts)]
        (is (= "L’Oréal Société" (:name row)) "legal source is preserved")
        (is (= "l’oréal société" (:name-ci row)) "exact lookup keeps accents")
        (is (= "loreal societe" (:name-key row)) "fuzzy lookup spends them")
        (is (re-find #"oreal|l'oreal" (:name-tsv row)) "phrase lookup stores lexemes")
        (is (= "plc" (:legal-form row)) "the suffix is structured, not indexed")))))

(deftest generated-columns-follow-the-source-without-application-maintenance-test
  (fixture/with-registry
    (fn [module]
      (let [id (random-uuid)]
        (fixture/register! module "ÉTAT" {:entity-id id})
        (jdbc/execute-one! (postgres/datasource)
                           ["UPDATE registry.entity SET name = 'Straße' WHERE entity_id = ?" id])
        (let [row (jdbc/execute-one!
                   (postgres/datasource)
                   ["SELECT name_ci, name_key FROM registry.entity WHERE entity_id = ?" id]
                   opts)]
          (is (= "strasse" (:name-ci row)))
          (is (= "strasse" (:name-key row))))))))

(deftest the-german-fallback-is-disposable-and-rebuildable-test
  (fixture/with-registry
    (fn [module]
      (let [id (random-uuid)]
        (fixture/register! module "Vermögensverwaltungsgesellschaft"
                           {:entity-id id :filing-language :de})
        (is (:found (registry/search module {:query "Verwaltung" :ui-language :de})))

        (jdbc/execute-one! (postgres/datasource)
                           ["UPDATE registry.entity SET german_parts = '' WHERE entity_id = ?" id])
        (is (:no-matches (registry/search module {:query "Verwaltung" :ui-language :de})))

        (is (= {:rebuilt 1 :search-version 1} (registry/rebuild-search! module)))
        (is (:found (registry/search module {:query "Verwaltung" :ui-language :de}))
            "retained filed names are enough to rebuild the derived part")))))

(deftest each-derived-column-has-the-index-for-its-own-access-pattern-test
  (fixture/with-registry
    (fn [_]
      (let [definitions (->> (jdbc/execute!
                              (postgres/admin)
                              ["SELECT indexname, indexdef FROM pg_indexes
                                  WHERE schemaname = 'registry'
                                  ORDER BY indexname"]
                              opts)
                             (map (juxt :indexname :indexdef))
                             (into {}))]
        (is (re-find #"text_pattern_ops" (definitions "entity_name_ci_prefix_idx")))
        (is (re-find #"gin.*name_key.*gin_trgm_ops"
                     (definitions "entity_name_key_trgm_idx")))
        (is (re-find #"gin.*name_tsv" (definitions "entity_name_tsv_idx")))))))
