(ns lab27.ordering.search-orders
  "The complete `Search orders` query slice.

  Ordering indexes its own orders, exactly as Catalog indexes its own
  products, and the two never meet. That is the cost this lab accepts rather
  than engineers around: you can have both lists, and you cannot rank a
  product against an order, because no single index contains both.

  There is no trigram fallback here and no path that reaches the customer's
  email. A misspelled product name returns nothing, which is the right answer
  when the alternative is a search box that fuzzy-matches personal data."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:query [:string {:min 1 :max 200}]]
   [:limit {:optional true} [:int {:min 1 :max 50}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def ^:private full-text
  "SELECT order_id, product_name, quantity, total_cents,
          ts_rank_cd(search_document, q, 32) AS rank
     FROM ordering.orders, websearch_to_tsquery('english', ?) q
    WHERE search_document @@ q
    ORDER BY rank DESC, order_id
    LIMIT ?")

(defn handle
  [{:keys [datasource]} {:keys [query limit]}]
  (if-let [hits (seq (jdbc/execute! datasource [full-text query (or limit 10)] opts))]
    {:found (vec hits)}
    {:no-matches query}))
