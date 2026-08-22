(ns lab29.ordering.catalog-price-changed
  "The complete integration-message slice for Catalog's public contract.

  It atomically claims a stable fact id and updates Ordering's local price
  book in one transaction. Re-delivery or republication is harmless, and no
  query reaches into Catalog's database.

  A delivery is transport headers plus the message. Note which of the two the
  inbox writes down: causation and correlation, which the business will still
  be asked about in three years, and not the traceparent, which is sampled and
  will have expired long before anyone asks."
  (:require [lab29.catalog.contract :as catalog-contract]
            [next.jdbc :as jdbc]))

(def Request
  [:map {:closed true}
   [:headers [:map-of :string :string]]
   [:message catalog-contract/PriceChanged]])

(defn handle!
  [{:keys [datasource]} {:keys [message]}]
  (let [message-id     (:message/id message)
        {:keys [causation-id correlation-id]} (:metadata message)
        {:keys [fact-id product-id product-name price-cents]} (:payload message)]
    (jdbc/with-transaction [tx datasource]
      (if (jdbc/execute-one!
           tx
           ["INSERT INTO ordering.inbox
               (fact_id, first_message_id, causation_id, correlation_id)
             VALUES (?, ?, ?, ?)
             ON CONFLICT (fact_id) DO NOTHING
             RETURNING fact_id"
            fact-id message-id causation-id correlation-id])
        (do
          (jdbc/execute-one!
           tx
           ["INSERT INTO ordering.price_book
               (product_id, product_name, current_price_cents)
             VALUES (?, ?, ?)
             ON CONFLICT (product_id) DO UPDATE
               SET product_name = EXCLUDED.product_name,
                   current_price_cents = EXCLUDED.current_price_cents"
            product-id product-name price-cents])
          {:accepted {:fact-id fact-id
                      :product-id product-id}})
        {:duplicate fact-id}))))
