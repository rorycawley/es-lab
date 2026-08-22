(ns lab29.websub.topics
  "Topics are resources, not facts. This is the whole conceptual difference.

  Internally, Catalog publishes `price-changed` and `product-described`:
  things that happened, each meaningful on its own, ordered, and lossy to
  miss. A WebSub topic is a **URL whose representation changed**, and what a
  subscriber receives is the representation -- not the delta, and not the
  reason.

  That has a consequence worth stating: WebSub gives you *convergence*, not a
  log. A subscriber that misses a notification and re-fetches the topic is
  correct. A subscriber that misses a domain event has a hole. It is also why
  WebSub cannot be the internal bus: Ordering needs to know that a price
  changed, not merely that the product resource is different now.

  The projection here folds both facts into one resource, and drops
  everything that is not disclosable on the way."
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn topic-url
  "The public address of one product's representation."
  [base-url product-id]
  (str base-url "/v1/products/" product-id))

(defn apply-price-changed!
  [tx {:keys [product-id product-name price-cents]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO websub.public_product (product_id, product_name, price_cents)
     VALUES (?, ?, ?)
     ON CONFLICT (product_id) DO UPDATE
       SET product_name = EXCLUDED.product_name,
           price_cents  = EXCLUDED.price_cents,
           updated_at   = now(),
           version      = websub.public_product.version + 1"
    product-id product-name price-cents]))

(defn apply-product-described!
  [tx {:keys [product-id description]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO websub.public_product (product_id, product_name, description, price_cents)
     VALUES (?, '', ?, 1)
     ON CONFLICT (product_id) DO UPDATE
       SET description = EXCLUDED.description,
           updated_at  = now(),
           version     = websub.public_product.version + 1"
    product-id description]))

(defn representation
  "What a subscriber and the public API both receive.

  Built from named columns rather than `SELECT *`, so a column added to this
  table later is not published by accident. That is not paranoia: this
  projection is the one place in the system whose readers are strangers."
  [datasource product-id]
  (when-let [row (jdbc/execute-one!
                  datasource
                  ["SELECT product_id, product_name, description, price_cents, version
                      FROM websub.public_product WHERE product_id = ?"
                   product-id]
                  opts)]
    {:product-id (str (:product-id row))
     :name       (:product-name row)
     :description (:description row)
     :price-cents (:price-cents row)
     :version     (:version row)}))

(defn body [representation] (json/write-str representation))
