(ns lab29.catalog.contract
  "Catalog's public integration contract: two facts, not one resource.

  A price changing and a description changing are different things that
  happened, and Catalog publishes them as such. It is not Catalog's business
  that one consumer folds both into a single public resource -- that is the
  WebSub adapter's projection, and the difference between a fact and a
  resource is the whole reason the two live on different sides of the
  boundary.

  Note what no payload carries: `supplier_cost_cents` is in the table and not
  in the contract, so no consumer can leak what it was never given."
  (:require [lab29.platform.message :as message]
            [malli.core :as m]))

(def price-changed-type :catalog/price-changed)
(def product-described-type :catalog/product-described)

(def ^:private Metadata
  [:map {:closed true}
   [:causation-id :uuid]
   [:correlation-id :uuid]])

(def PriceChanged
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :integration-event]]
   [:event/type [:= price-changed-type]]
   [:metadata Metadata]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:product-id :uuid]
     [:product-name [:string {:min 1 :max 80}]]
     [:price-cents [:int {:min 1 :max 100000}]]]]])

(def ProductDescribed
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :integration-event]]
   [:event/type [:= product-described-type]]
   [:metadata Metadata]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:product-id :uuid]
     [:description [:string {:min 1 :max 2000}]]]]])

(defn price-changed
  [message-id fact-id causation-id correlation-id product-id product-name price-cents]
  (message/integration-event
   message-id price-changed-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:fact-id fact-id :product-id product-id
    :product-name product-name :price-cents price-cents}))

(defn product-described
  [message-id fact-id causation-id correlation-id product-id description]
  (message/integration-event
   message-id product-described-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:fact-id fact-id :product-id product-id :description description}))

(defn price-changed? [msg] (m/validate PriceChanged msg))
