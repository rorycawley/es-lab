(ns lab25.ordering.place-order
  "The complete `Place order` command slice.

  It reads Ordering's local price copy and writes Ordering's order table in one
  transaction. There is no generic repository and no call into Catalog."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:order-id :uuid]
   [:product-id :uuid]
   [:quantity [:int {:min 1 :max 50}]]])

(defn price-order
  "Pure calculation kept beside the slice until its complexity earns a model."
  [{:keys [order-id product-id quantity]}
   {:keys [product-name current-price-cents]}]
  {:order-id         order-id
   :product-id       product-id
   :product-name     product-name
   :quantity         quantity
   :unit-price-cents current-price-cents
   :total-cents      (* quantity current-price-cents)})

(defn handle!
  [{:keys [datasource]} {:keys [product-id] :as request}]
  (jdbc/with-transaction [tx datasource]
    (if-let [price (jdbc/execute-one!
                    tx
                    ["SELECT product_name, current_price_cents
                        FROM ordering.price_book
                       WHERE product_id = ?"
                     product-id]
                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (let [{:keys [order-id product-name quantity unit-price-cents total-cents]
             :as order} (price-order request price)]
        (jdbc/execute-one!
         tx
         ["INSERT INTO ordering.orders
             (order_id, product_id, product_name, quantity,
              unit_price_cents, total_cents)
           VALUES (?, ?, ?, ?, ?, ?)"
          order-id product-id product-name quantity unit-price-cents total-cents])
        {:accepted order})
      {:rejected :price-unavailable
       :because  "Ordering has not received a price for this product"})))
