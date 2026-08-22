(ns lab28.ordering.place-order
  "The complete `Place order` command slice.

  It reads Ordering's local price copy and writes Ordering's order table in one
  transaction. Exact retries return the captured order. There is no generic
  repository and no call into Catalog.

  New in lab 26: the order carries the customer's email, because a receipt has
  to go somewhere. It is part of what makes two requests the same request, so
  it is compared on retry — and it is stripped from every response, because a
  caller asking what an order cost has not asked who placed it."
  (:require [lab28.ordering.contract :as contract]
            [lab28.platform.telemetry :as telemetry]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:order-id :uuid]
   [:correlation-id :uuid]
   [:product-id :uuid]
   [:quantity [:int {:min 1 :max 50}]]
   [:customer-email [:string {:min 3 :max 254}]]
   [:payment-method [:string {:min 1 :max 100}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

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

(defn- order-by-id [db order-id]
  (jdbc/execute-one!
   db
   ["SELECT order_id, product_id, product_name, quantity,
            unit_price_cents, total_cents, customer_email, payment_method
       FROM ordering.orders
      WHERE order_id = ?"
    order-id]
   opts))

(defn- prior-result
  [db {:keys [order-id product-id quantity customer-email]}]
  (when-let [order (order-by-id db order-id)]
    (if (= {:product-id product-id :quantity quantity :customer-email customer-email}
           (select-keys order [:product-id :quantity :customer-email]))
      {:accepted (dissoc order :customer-email :payment-method)}
      (throw (ex-info "Order id already identifies another request"
                      {:reason :order-id-collision :order-id order-id})))))

(defn- announce!
  [tx {:keys [order-id correlation-id product-name quantity total-cents
              customer-email payment-method fact-id message-id]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO ordering.outbox
       (message_id, message_type, fact_id, causation_id, correlation_id,
        traceparent, order_id, product_name, quantity, total_cents,
        customer_email, payment_method, published)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)"
    message-id
    (str (namespace contract/order-placed-type) "/"
         (name contract/order-placed-type))
    fact-id order-id correlation-id
    (get (telemetry/trace-headers) "traceparent")
    order-id product-name quantity total-cents customer-email payment-method]))

(defn handle!
  [{:keys [datasource new-id]} {:keys [product-id] :as request}]
  (or (prior-result datasource request)
      (jdbc/with-transaction [tx datasource]
        (if-let [price (jdbc/execute-one!
                        tx
                        ["SELECT product_name, current_price_cents
                            FROM ordering.price_book
                           WHERE product_id = ?"
                         product-id]
                        opts)]
          (let [{:keys [order-id product-name quantity unit-price-cents total-cents]}
                (price-order request price)
                inserted (jdbc/execute-one!
                          tx
                          ["INSERT INTO ordering.orders
                              (order_id, correlation_id, product_id, product_name, quantity,
                               unit_price_cents, total_cents, customer_email, payment_method)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (order_id) DO NOTHING
                            RETURNING order_id, product_id, product_name, quantity,
                                      unit_price_cents, total_cents"
                           order-id (:correlation-id request) product-id product-name quantity
                           unit-price-cents total-cents (:customer-email request)
                           (:payment-method request)]
                          opts)]
            (if inserted
              (do
                (announce! tx {:order-id order-id
                               :correlation-id (:correlation-id request)
                               :product-name product-name
                               :quantity quantity
                               :total-cents total-cents
                               :customer-email (:customer-email request)
                               :payment-method (:payment-method request)
                               :fact-id (new-id)
                               :message-id (new-id)})
                {:accepted inserted})
              (prior-result tx request)))
          {:rejected :price-unavailable
           :because  "Ordering has not received a price for this product"}))))
