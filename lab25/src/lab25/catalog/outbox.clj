(ns lab25.catalog.outbox
  "Catalog's module-owned outbox.

  Updating today's price and recording its integration message share Catalog's
  transaction. Delivery crosses into another database and therefore cannot."
  (:require [lab25.catalog.contract :as contract]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn pending
  [{:keys [datasource]}]
  (mapv (fn [{:keys [message-id product-id product-name price-cents]}]
          (contract/price-changed message-id product-id product-name price-cents))
        (jdbc/execute!
         datasource
         ["SELECT message_id, product_id, product_name, price_cents
             FROM catalog.outbox
            WHERE published = FALSE
            ORDER BY created_order"]
         {:builder-fn rs/as-unqualified-kebab-maps})))

(defn mark-published!
  [{:keys [datasource]} message-id]
  (jdbc/execute-one!
   datasource
   ["UPDATE catalog.outbox SET published = TRUE WHERE message_id = ?"
    message-id]))
