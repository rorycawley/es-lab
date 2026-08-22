(ns lab28.payments.contract
  "Payments' public integration contract.

  Notifications may depend on this namespace and on nothing else in Payments.
  Note the absence of the provider reference: which provider took the money is
  Payments' business, and putting it in a public contract would let every
  downstream module quietly acquire an opinion about one.

  The name is `succeeded` rather than `settled` because two different internal
  states can produce it -- authorized synchronously, or settled later by a
  callback -- and a contract that named one of them would be lying half the
  time. Downstream needs to know the money is ours, not which road it took.")

(def payment-succeeded-type :payments/payment-succeeded)

(def PaymentSucceeded
  [:map {:closed true}
   [:message/id :uuid]
   [:message/type [:= payment-succeeded-type]]
   [:metadata
    [:map {:closed true}
     [:causation-id :uuid]
     [:correlation-id :uuid]]]
   [:payload
    [:map {:closed true}
     [:fact-id :uuid]
     [:payment-id :uuid]
     [:order-id :uuid]
     [:amount-cents [:int {:min 1 :max 100000000}]]
     [:customer-email [:string {:min 3 :max 254}]]]]])

(defn payment-succeeded
  [message-id fact-id causation-id correlation-id
   payment-id order-id amount-cents customer-email]
  {:message/id   message-id
   :message/type payment-succeeded-type
   :metadata     {:causation-id causation-id
                  :correlation-id correlation-id}
   :payload      {:fact-id fact-id
                  :payment-id payment-id
                  :order-id order-id
                  :amount-cents amount-cents
                  :customer-email customer-email}})
