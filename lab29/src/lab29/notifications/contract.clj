(ns lab29.notifications.contract
  "Notifications' public contract: one command, no published facts.

  A module that publishes nothing is not a broken module. Notifications is a
  leaf: it is asked to do something and it does it, and nobody downstream
  needs to know. Declaring the empty set is more useful than omitting the
  contract, because it makes \"nobody consumes anything from here\" a checked
  statement rather than an absence."
  (:require [lab29.platform.message :as message]))

(def send-receipt-type :notifications/send-receipt)

(def SendReceipt
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :command]]
   [:command/type [:= send-receipt-type]]
   [:metadata
    [:map {:closed true}
     [:causation-id :uuid]
     [:correlation-id :uuid]]]
   [:data
    [:map {:closed true}
     [:receipt-id :uuid]
     [:order-id :uuid]
     [:amount-cents [:int {:min 1 :max 100000000}]]
     [:customer-email [:string {:min 3 :max 254}]]]]])

(defn send-receipt
  [message-id causation-id correlation-id
   receipt-id order-id amount-cents customer-email]
  (message/command
   message-id send-receipt-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:receipt-id receipt-id :order-id order-id
    :amount-cents amount-cents :customer-email customer-email}))
