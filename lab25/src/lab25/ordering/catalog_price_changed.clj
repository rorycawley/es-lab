(ns lab25.ordering.catalog-price-changed
  "The complete integration-message slice for Catalog's public contract.

  It owns an inbox and Ordering's local price book. Re-delivery is harmless,
  and no query reaches into Catalog's database."
  (:require [lab25.catalog.contract :as catalog-contract]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request catalog-contract/PriceChanged)

(defn handle!
  [{:keys [datasource]} message]
  (let [message-id (:message/id message)
        {:keys [product-id product-name price-cents]} (:payload message)]
    (jdbc/with-transaction [tx datasource]
      (if (jdbc/execute-one!
           tx
           ["SELECT message_id FROM ordering.inbox WHERE message_id = ?"
            message-id]
           {:builder-fn rs/as-unqualified-kebab-maps})
        {:duplicate message-id}
        (do
          (jdbc/execute-one!
           tx
           ["INSERT INTO ordering.inbox (message_id) VALUES (?)" message-id])
          (jdbc/execute-one!
           tx
           ["INSERT INTO ordering.price_book
               (product_id, product_name, current_price_cents)
             VALUES (?, ?, ?)
             ON CONFLICT (product_id) DO UPDATE
               SET product_name = EXCLUDED.product_name,
                   current_price_cents = EXCLUDED.current_price_cents"
            product-id product-name price-cents])
          {:accepted {:message-id message-id
                      :product-id product-id}})))))
