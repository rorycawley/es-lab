(ns lab29.ordering.contract
  "Ordering's public integration contract.

  One fact: an order was placed. Who cares is not Ordering's business -- which
  is the difference between this and the command it sends to Payments, where
  the destination is the whole point."
  (:require [lab29.platform.message :as message]))

(def order-placed-type :ordering/order-placed)

(def OrderPlaced
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :integration-event]]
   [:event/type [:= order-placed-type]]
   [:metadata
    [:map {:closed true}
     [:causation-id :uuid]
     [:correlation-id :uuid]]]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:order-id :uuid]
     [:product-name [:string {:min 1 :max 80}]]
     [:quantity [:int {:min 1 :max 50}]]
     [:total-cents [:int {:min 1 :max 100000000}]]]]])

(defn order-placed
  [message-id fact-id causation-id correlation-id
   order-id product-name quantity total-cents]
  (message/integration-event
   message-id order-placed-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:fact-id fact-id :order-id order-id :product-name product-name
    :quantity quantity :total-cents total-cents}))
