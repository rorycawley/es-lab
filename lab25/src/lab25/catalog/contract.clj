(ns lab25.catalog.contract
  "The Catalog module's public integration contract.

  Ordering may depend on this namespace. It may not depend on Catalog's
  handlers, SQL, tables or internal state. The map is an integration message,
  so the fact being carried belongs under `:payload` (lab 3)."
  (:require [malli.core :as m]))

(def price-changed-type :catalog/price-changed)

(def PriceChanged
  [:map
   [:message/id :uuid]
   [:message/type [:= price-changed-type]]
   [:payload
    [:map
     [:product-id :uuid]
     [:product-name :string]
     [:price-cents pos-int?]]]])

(defn price-changed
  [message-id product-id product-name price-cents]
  {:message/id   message-id
   :message/type price-changed-type
   :payload      {:product-id    product-id
                  :product-name  product-name
                  :price-cents   price-cents}})

(defn price-changed? [message]
  (m/validate PriceChanged message))
