(ns lab27.catalog.contract
  "The Catalog module's public integration contract, unchanged from lab 25.

  Ordering may depend on this namespace. It may not depend on Catalog's
  handlers, SQL, tables or internal state. The map is an integration message,
  so its stable fact id and data belong under `:payload`; delivery identity
  and trace metadata remain separate (labs 3 and 4).

  Unchanged is the point. Lab 26 added W3C trace context to every publication
  and this contract did not move, because trace context travels in a delivery's
  transport headers beside the message rather than inside it."
  (:require [malli.core :as m]))

(def price-changed-type :catalog/price-changed)

(def PriceChanged
  [:map {:closed true}
   [:message/id :uuid]
   [:message/type [:= price-changed-type]]
   [:metadata
    [:map {:closed true}
     [:causation-id :uuid]
     [:correlation-id :uuid]]]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:product-id :uuid]
     [:product-name [:string {:min 1 :max 80}]]
     [:price-cents [:int {:min 1 :max 100000}]]]]])

(defn price-changed
  [message-id fact-id causation-id correlation-id
   product-id product-name price-cents]
  {:message/id   message-id
   :message/type price-changed-type
   :metadata     {:causation-id causation-id
                  :correlation-id correlation-id}
   :payload      {:fact-id     fact-id
                  :product-id   product-id
                  :product-name product-name
                  :price-cents  price-cents}})

(defn price-changed? [message]
  (m/validate PriceChanged message))
