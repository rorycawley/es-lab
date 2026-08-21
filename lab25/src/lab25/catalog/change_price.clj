(ns lab25.catalog.change-price
  "The complete `Change price` command slice: request, transaction, response."
  (:require [lab25.catalog.contract :as contract]
            [next.jdbc :as jdbc]))

(def Request
  [:map {:closed true}
   [:product-id :uuid]
   [:product-name [:string {:min 1 :max 80}]]
   [:price-cents [:int {:min 1 :max 100000}]]])

(defn handle!
  [{:keys [datasource new-id]} request]
  (let [{:keys [product-id product-name price-cents]} request
        message-id (new-id)
        message-type (str (namespace contract/price-changed-type)
                          "/" (name contract/price-changed-type))]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute-one!
       tx
       ["INSERT INTO catalog.product
           (product_id, product_name, current_price_cents)
         VALUES (?, ?, ?)
         ON CONFLICT (product_id) DO UPDATE
           SET product_name = EXCLUDED.product_name,
               current_price_cents = EXCLUDED.current_price_cents"
        product-id product-name price-cents])
      (jdbc/execute-one!
       tx
       ["INSERT INTO catalog.outbox
           (message_id, message_type, product_id, product_name, price_cents, published)
         VALUES (?, ?, ?, ?, ?, FALSE)"
        message-id message-type
        product-id product-name price-cents]))
    {:accepted {:product-id          product-id
                :product-name        product-name
                :current-price-cents price-cents
                :message-id          message-id}}))
