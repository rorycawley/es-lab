(ns lab29.catalog.change-price
  "The complete `Change price` command slice: request identity, validation
  shape, atomic product/ledger/outbox transaction and response."
  (:require [lab29.catalog.contract :as contract]
            [lab29.catalog.outbox :as outbox]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:command-id :uuid]
   [:correlation-id :uuid]
   [:product-id :uuid]
   [:product-name [:string {:min 1 :max 80}]]
   [:price-cents [:int {:min 1 :max 100000}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- accepted
  [{:keys [product-id product-name price-cents]} fact-id]
  {:accepted {:product-id product-id
              :product-name product-name
              :current-price-cents price-cents
              :fact-id fact-id}})

(defn- same-request?
  [row request]
  (= (select-keys row [:product-id :product-name :price-cents])
     (select-keys request [:product-id :product-name :price-cents])))

(defn- prior-result
  [db {:keys [command-id] :as request}]
  (when-let [row (jdbc/execute-one!
                  db
                  ["SELECT product_id, product_name, price_cents, fact_id
                      FROM catalog.command_ledger
                     WHERE command_id = ?"
                   command-id]
                  opts)]
    (if (same-request? row request)
      (accepted request (:fact-id row))
      (throw (ex-info "Command id already identifies another price change"
                      {:reason :command-id-collision
                       :command-id command-id})))))

(defn- claim!
  [tx {:keys [command-id correlation-id product-id product-name price-cents]}
   fact-id message-id]
  (jdbc/execute-one!
   tx
   ["INSERT INTO catalog.command_ledger
       (command_id, correlation_id, product_id, product_name, price_cents,
        fact_id, message_id)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (command_id) DO NOTHING
     RETURNING command_id"
    command-id correlation-id product-id product-name price-cents fact-id message-id]
   opts))

(defn- commit-new!
  [tx {:keys [command-id correlation-id product-id product-name price-cents] :as request}
   fact-id message-id]
  (jdbc/execute-one!
   tx
   ["INSERT INTO catalog.product
       (product_id, product_name, current_price_cents)
     VALUES (?, ?, ?)
     ON CONFLICT (product_id) DO UPDATE
       SET product_name = EXCLUDED.product_name,
           current_price_cents = EXCLUDED.current_price_cents"
    product-id product-name price-cents])
  ;; The fact and the message announcing it, in one transaction, as they have
  ;; been since lab 25. What changed is that the outbox now takes a whole
  ;; envelope built by the contract rather than a row of typed columns.
  (outbox/enqueue! tx (contract/price-changed message-id fact-id command-id
                                              correlation-id product-id
                                              product-name price-cents))
  (accepted request fact-id))

(defn handle!
  [{:keys [datasource new-id]} request]
  (or (prior-result datasource request)
      (let [fact-id    (new-id)
            message-id (new-id)]
        (jdbc/with-transaction [tx datasource]
          (if (claim! tx request fact-id message-id)
            (commit-new! tx request fact-id message-id)
            (prior-result tx request))))))
