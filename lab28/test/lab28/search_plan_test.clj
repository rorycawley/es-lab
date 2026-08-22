(ns lab28.search-plan-test
  "Measured, not asserted -- lab 16's rule, applied to the query planner.

  `EXPLAIN` answers a narrower question than \"is it fast\". It says which
  options the planner had and which it took, and those are different claims
  with different stability. Whether the planner *chooses* an index depends on
  table statistics and will drift; whether an index exists that it *could*
  choose does not. This suite asserts the second. `bb demo` shows the first,
  where an observation that changes with the data costs nothing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab28.corpus :as corpus]
            [lab28.naive-search :as naive]
            [lab28.postgres :as postgres]
            [next.jdbc :as jdbc]))

(def ^:private fts-sql
  "SELECT product_id FROM catalog.product, websearch_to_tsquery('english', ?) q
    WHERE search_document @@ q
    ORDER BY ts_rank_cd(search_document, q, 32) DESC LIMIT 10")

(def ^:private trigram-sql
  "SELECT product_id FROM catalog.product
    WHERE product_name % ? ORDER BY similarity(product_name, ?) DESC LIMIT 10")

(defn- with-corpus [f]
  (postgres/truncate!)
  (let [datasource (jdbc/get-datasource (:catalog (postgres/config)))]
    (corpus/seed! datasource)
    (f datasource)))

(deftest a-leading-wildcard-like-has-no-index-it-could-ever-use-test
  (with-corpus
    (fn [db]
      (let [like-plan (naive/explain-without-seqscan
                       db naive/like-sql [(str "%" corpus/rare-term "%") 10])
            fts-plan  (naive/explain-without-seqscan
                       db fts-sql [corpus/rare-term])]
        (testing "told not to scan the table, full-text search reaches for GIN"
          (is (str/includes? fts-plan "product_search_idx"))
          (is (str/includes? fts-plan "Bitmap Index Scan")))
        (testing "and LIKE scans anyway, because there is nothing else"
          (is (str/includes? like-plan "Seq Scan"))
          (is (not (str/includes? like-plan "Index Scan"))
              "this is structural, not a tuning difference"))))))

(deftest the-trigram-index-serves-the-misspelling-test
  (with-corpus
    (fn [db]
      (let [plan (naive/explain-without-seqscan
                  db trigram-sql ["szechwan peppercorn sorbet"
                                  "szechwan peppercorn sorbet"])]
        (is (str/includes? plan "product_name_trgm_idx"))))))

(deftest like-cannot-stem-at-any-size-test
  (with-corpus
    (fn [db]
      (testing "50,000 rows say 'tasting note'"
        (is (empty? (naive/like-search db "tasting notes" 10))
            "LIKE is looking for the letters, and they are not there")
        (is (seq (jdbc/execute!
                  db [fts-sql "tasting notes"]))
            "full-text search is looking for the words, and they are"))
      (testing "no index would have fixed that"
        (is (empty? (naive/like-search db "flavours" 10)))))))
