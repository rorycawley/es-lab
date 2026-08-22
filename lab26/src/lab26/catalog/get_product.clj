(ns lab26.catalog.get-product
  "The complete `Get product` query slice, unchanged from lab 25."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:product-id :uuid]])

(defn handle
  [{:keys [datasource]} {:keys [product-id]}]
  (if-let [row (jdbc/execute-one!
                datasource
                ["SELECT product_id, product_name, current_price_cents
                    FROM catalog.product
                   WHERE product_id = ?"
                 product-id]
                {:builder-fn rs/as-unqualified-kebab-maps})]
    {:found row}
    {:not-found product-id}))
