(ns lab29.catalog.search-products
  "The complete `Search products` query slice.

  It reads Catalog's own table and Catalog's own index, which is the whole
  architectural claim of this lab: the index lives with the data it indexes,
  so there is nothing to keep in sync and nothing to rebuild from a feed.

  Three responses, because a search box really does have three outcomes and
  collapsing them loses the one that matters. `:no-matches` is the metric a
  search feature lives or dies by."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:query [:string {:min 1 :max 200}]]
   [:limit {:optional true} [:int {:min 1 :max 50}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def ^:private default-limit 10)

;; `websearch_to_tsquery` and not `to_tsquery`.
;;
;; It is the only parser that cannot raise a syntax error, which makes it the
;; only one safe to hand a string a user typed. It also gives that user a
;; syntax they already know from every search box they have ever used: bare
;; words are ANDed, "quoted words" must be adjacent, OR is a choice and a
;; leading minus excludes. `to_tsquery` would turn a stray bracket into a 500.
(def ^:private full-text
  "SELECT product_id, product_name, current_price_cents,
          ts_rank_cd(search_document, q, 32) AS rank,
          ts_headline('english', description, q,
                      'MaxWords=14, MinWords=4, StartSel=«, StopSel=»') AS snippet
     FROM catalog.product, websearch_to_tsquery('english', ?) q
    WHERE search_document @@ q
    ORDER BY rank DESC, product_name
    LIMIT ?")

;; The fallback, for when the lexemes do not match because the word was
;; misspelled. `%` compares trigrams rather than words, so it has no opinion
;; about English and no idea what the user meant -- which is why it is a
;; suggestion and not a result.
(def ^:private by-similarity
  "SELECT product_id, product_name, similarity(product_name, ?) AS similarity
     FROM catalog.product
    WHERE product_name % ?
    ORDER BY similarity DESC, product_name
    LIMIT ?")

(defn handle
  [{:keys [datasource]} {:keys [query limit]}]
  (let [limit (or limit default-limit)]
    (if-let [hits (seq (jdbc/execute! datasource [full-text query limit] opts))]
      {:found (vec hits)}
      (if-let [near (seq (jdbc/execute! datasource [by-similarity query query limit] opts))]
        {:did-you-mean (vec near)}
        {:no-matches query}))))
