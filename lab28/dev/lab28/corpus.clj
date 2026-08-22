(ns lab28.corpus
  "Fifty thousand products, so the query planner has a real decision to make.

  A plan assertion over three rows proves nothing: Postgres will sequentially
  scan a tiny table no matter what indexes exist, and it is right to. The
  corpus exists so that `EXPLAIN` is answering a question about indexes rather
  than a question about table size."
  (:require [next.jdbc :as jdbc]))

(def rows 50000)

;; One product nothing else resembles, so a query for it is genuinely
;; selective. Every other row draws from a small vocabulary.
(def rare-product-name "szechuan peppercorn sorbet")
(def rare-term "szechuan")
(def rare-description "a rare and numbing tasting note")

(def ^:private seed-sql
  "INSERT INTO catalog.product (product_id, product_name, description, current_price_cents)
   SELECT gen_random_uuid(),
          (ARRAY['sorbet','gelato','sundae','parfait','granita','affogato',
                 'semifreddo','kulfi'])[1 + (i %% 8)] || ' ' || i,
          'batch ' || i || ' tasting note mentioning ' ||
          (ARRAY['almond','banana','caramel','damson','elderflower','fig','ginger',
                 'honey','juniper','kiwi','lemon','mango','nutmeg','orange','peach',
                 'quince','rhubarb','saffron','tamarind','walnut'])[1 + (i %% 20)],
          100 + (i %% 500)
     FROM generate_series(1, %d) i")

(defn seed!
  "Fill catalog.product, then make the planner aware of it.

  `VACUUM ANALYZE` rather than `ANALYZE`: statistics are what the planner
  reasons with, and a bulk load that skips them leaves it estimating against a
  table it believes is empty. It must run outside a transaction, which is why
  this uses the datasource directly."
  [datasource]
  (jdbc/execute! datasource [(format seed-sql rows)])
  (jdbc/execute! datasource
                 ["INSERT INTO catalog.product
                     (product_id, product_name, description, current_price_cents)
                   VALUES (gen_random_uuid(), ?, ?, 700)"
                  rare-product-name rare-description])
  (jdbc/execute! datasource ["VACUUM ANALYZE catalog.product"])
  (inc rows))
