(ns lab28.payments.get-payment
  "The complete `Get payment` query slice.

  `gateway_reference` is returned because an operator chasing a payment needs
  the string to paste into the provider's dashboard. `customer_email` is not,
  which is lab 24's response shaping."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:order-id :uuid]])

(defn handle
  [{:keys [datasource]} {:keys [order-id]}]
  (if-let [row (jdbc/execute-one!
                datasource
                ["SELECT payment_id, order_id, amount_cents, currency, status,
                         gateway_reference, decline_reason
                    FROM payments.payment WHERE order_id = ?"
                 order-id]
                {:builder-fn rs/as-unqualified-kebab-maps})]
    {:found row}
    {:not-found order-id}))
