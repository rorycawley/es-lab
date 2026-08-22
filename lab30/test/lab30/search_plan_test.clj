(ns lab30.search-plan-test
  "Indexes are structural claims. The planner may choose differently for a
  tiny fixture, so these tests disable sequential scans and ask whether each
  rung has an index it can use."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [lab30.postgres :as postgres]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- seed! [db]
  (jdbc/execute!
   db
   ["INSERT INTO registry.entity
       (entity_id, reg_no, name, legal_form, filing_lang, status,
        registered_on, german_parts)
     SELECT md5(i::text)::uuid,
            'BULK-' || lpad(i::text, 6, '0'),
            'Example Société ' || lpad(i::text, 6, '0'),
            'llc'::registry.legal_form, 'fr'::registry.lang, 'active',
            DATE '2020-01-01', ''
       FROM generate_series(1, 10000) i"])
  (jdbc/execute! db ["ANALYZE registry.entity"]))

(defn- plan [db sql params]
  (jdbc/with-transaction [tx db]
    (jdbc/execute! tx ["SET LOCAL enable_seqscan = off"])
    (->> (jdbc/execute! tx (into [(str "EXPLAIN (COSTS OFF) " sql)] params)
                        {:builder-fn rs/as-unqualified-arrays})
         (map first)
         (str/join "\n"))))

(defn- with-corpus [f]
  (postgres/truncate!)
  (let [db (postgres/datasource)]
    (seed! db)
    (f db)))

(deftest registration-number-has-a-btree-test
  (with-corpus
    (fn [db]
      (let [p (plan db
                    "SELECT entity_id FROM registry.entity WHERE reg_no = ?"
                    ["BULK-009999"])]
        (is (str/includes? p "entity_reg_no_key"))))))

(deftest autocomplete-has-a-prefix-btree-test
  (with-corpus
    (fn [db]
      (let [p (plan db
                    "SELECT entity_id FROM registry.entity
                      WHERE name_ci LIKE casefold(normalize(?::text, NFC)) || '%'
                        AND removed_at IS NULL"
                    ["Example Société 009"])]
        (is (str/includes? p "entity_name_ci_prefix_idx"))))))

(deftest phrase-search-has-a-gin-index-test
  (with-corpus
    (fn [db]
      (let [p (plan db
                    "SELECT entity_id FROM registry.entity
                      WHERE name_tsv @@ websearch_to_tsquery('registry.fr'::regconfig, ?)
                        AND removed_at IS NULL"
                    ["Société 009999"])]
        (is (str/includes? p "entity_name_tsv_idx"))
        (is (str/includes? p "Bitmap Index Scan"))))))

(deftest strict-word-fuzziness-has-a-gin-index-test
  (with-corpus
    (fn [db]
      (let [p (plan db
                    "SELECT entity_id FROM registry.entity
                      WHERE registry.search_key(?) <<% name_key
                        AND removed_at IS NULL"
                    ["Societe 00999"])]
        (is (str/includes? p "entity_name_key_trgm_idx"))))))
