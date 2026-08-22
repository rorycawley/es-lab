(ns lab28.ordering.get-order
  "The complete `Get order` query slice, unchanged from lab 25."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:order-id :uuid]])

(defn handle
  [{:keys [datasource]} {:keys [order-id]}]
  (if-let [row (jdbc/execute-one!
                datasource
                ["SELECT order_id, product_id, product_name, quantity,
                         unit_price_cents, total_cents
                    FROM ordering.orders
                   WHERE order_id = ?"
                 order-id]
                {:builder-fn rs/as-unqualified-kebab-maps})]
    {:found row}
    {:not-found order-id}))
