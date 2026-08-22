(ns lab29.payments.contract
  "Payments' public contract: the command it accepts and the fact it publishes.

  The command's schema lives here, with the module that owns the capability,
  rather than with the module that wants it. That is the dependency direction
  the messaging document asks for: a requester depends on the requestee's
  contract, so there is one definition of what it means to ask for a charge
  and it belongs to whoever has to honour it.

  Note the absence of a provider reference in the published fact: which
  provider took the money is Payments' business, and putting it in a public
  contract would let every downstream module quietly acquire an opinion."
  (:require [lab29.platform.message :as message]))

(def charge-order-type :payments/charge-order)
(def payment-succeeded-type :payments/payment-succeeded)

(def ^:private Metadata
  [:map {:closed true}
   [:causation-id :uuid]
   [:correlation-id :uuid]])

(def ChargeOrder
  "Please take this money. Exactly one destination, and it may be refused."
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :command]]
   [:command/type [:= charge-order-type]]
   [:metadata Metadata]
   [:data
    [:map {:closed true}
     [:order-id :uuid]
     [:order-fact-id :uuid]
     [:total-cents [:int {:min 1 :max 100000000}]]
     [:customer-email [:string {:min 3 :max 254}]]
     [:payment-method [:string {:min 1 :max 100}]]]]])

(def PaymentSucceeded
  "The money is ours. Whoever cares."
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :integration-event]]
   [:event/type [:= payment-succeeded-type]]
   [:metadata Metadata]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:payment-id :uuid]
     [:order-id :uuid]
     [:amount-cents [:int {:min 1 :max 100000000}]]
     [:customer-email [:string {:min 3 :max 254}]]]]])

(defn charge-order
  [message-id causation-id correlation-id
   order-id order-fact-id total-cents customer-email payment-method]
  (message/command
   message-id charge-order-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:order-id order-id :order-fact-id order-fact-id :total-cents total-cents
    :customer-email customer-email :payment-method payment-method}))

(defn payment-succeeded
  [message-id fact-id causation-id correlation-id
   payment-id order-id amount-cents customer-email]
  (message/integration-event
   message-id payment-succeeded-type
   {:causation-id causation-id :correlation-id correlation-id}
   {:fact-id fact-id :payment-id payment-id :order-id order-id
    :amount-cents amount-cents :customer-email customer-email}))
