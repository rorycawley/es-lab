(ns lab29.search-index-test
  "The search index is a projection, and these are the properties that make it
  one.

  Lab 9 defined a read model as a fold you already did: derived, disposable,
  and rebuildable from retained sources. Almost every way a search index goes
  wrong is a way of forgetting one of those three."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.fixture :as fixture]
            [lab29.postgres :as postgres]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- catalog-db [] (jdbc/get-datasource (:catalog (postgres/config))))

(defn- stock! [catalog description]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents 300})
  (catalog/describe-product! catalog {:command-id (random-uuid)
                                      :correlation-id (random-uuid)
                                      :product-id vanilla
                                      :description description}))

(defn- names [response] (mapv :product-name (:found response)))

;; ---------------------------------------------------------------------------

(deftest the-index-is-derived-and-nothing-maintains-it-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (stock! catalog "a creamy vanilla flavour")
      (is (= ["vanilla"] (names (catalog/search catalog {:query "creamy"}))))

      (catalog/describe-product! catalog {:command-id (random-uuid)
                                          :correlation-id (random-uuid)
                                          :product-id vanilla
                                          :description "now bracing and sharp"})
      (testing "one UPDATE to the source text, and the index simply agrees"
        (is (:no-matches (catalog/search catalog {:query "creamy"})))
        (is (= ["vanilla"] (names (catalog/search catalog {:query "bracing"})))))
      (testing "no slice contains index maintenance, because there is none"
        (is (not (re-find #"search_document"
                          (slurp "src/lab29/catalog/describe_product.clj"))))))))

(deftest the-index-is-disposable-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (stock! catalog "a creamy vanilla flavour with real pods")
      (let [before (catalog/search catalog {:query "pods"})]
        (jdbc/execute! (catalog-db) ["REINDEX INDEX catalog.product_search_idx"])
        (is (= before (catalog/search catalog {:query "pods"}))
            "lab 9's rule: rebuilding from zero equals what was there already")

        (jdbc/execute! (catalog-db) ["DROP INDEX catalog.product_search_idx"])
        (is (= before (catalog/search catalog {:query "pods"}))
            "and with no index at all the answers are still correct, only slow")

        (jdbc/execute! (catalog-db)
                       ["CREATE INDEX product_search_idx
                           ON catalog.product USING GIN (search_document)"])))))

(deftest the-text-search-configuration-is-the-fold-version-test
  ;; Lab 17: a snapshot records the version of the fold that produced it,
  ;; because changing `evolve` can invalidate cached state without any event
  ;; changing. `'english'` is that version here. It is baked into every stored
  ;; vector, and a question asked under a different configuration is a
  ;; different question.
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (stock! catalog "a creamy vanilla flavour")
      (is (= ["vanilla"] (names (catalog/search catalog {:query "flavours"})))
          "under english, flavours and flavour are one lexeme")
      (let [row (jdbc/execute-one!
                 (catalog-db)
                 ["SELECT to_tsvector('simple', description)
                          @@ websearch_to_tsquery('simple', 'flavours') AS matched
                     FROM catalog.product WHERE product_id = ?" vanilla])]
        (is (false? (:matched row))
            "under simple it is not, so re-analysing the corpus is the only
             way to change the configuration -- which needs the corpus")))))

(deftest the-vector-cannot-reconstruct-the-document-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (stock! catalog "a creamy vanilla flavour with real pods")
      (let [{:keys [vector description]}
            (jdbc/execute-one!
             (catalog-db)
             ["SELECT search_document::text AS vector, description
                 FROM catalog.product WHERE product_id = ?" vanilla]
             {:builder-fn rs/as-unqualified-kebab-maps})]
        (is (re-find #"creami" vector)
            "the vector holds stems, not words")
        (doseq [dropped ["'with'" "'a'" "'and'"]]
          (is (not (str/includes? vector dropped))
              (str "stop words are gone: " dropped)))
        (is (str/includes? vector "'real'")
            "content words survive, so this is lossy rather than lossless")
        (is (re-find #"with real pods" description)
            "which is why the source text is retained beside it, not replaced by it")))))
