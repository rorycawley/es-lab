(ns lab28.ordering.contract
  "Ordering's public integration contract.

  New in lab 28. Ordering has always had something other modules would want to
  know -- that an order was placed -- and until now nobody was listening.

  The customer's email is in this contract on purpose, and it is the first time
  personal data has crossed a module boundary in this repository. That is a
  decision, not a default: Notifications cannot send a receipt without it, and
  labs 15 and 26 both apply to the copy that now exists on the other side.")

(def order-placed-type :ordering/order-placed)

(def OrderPlaced
  [:map {:closed true}
   [:message/id :uuid]
   [:message/type [:= order-placed-type]]
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
     [:total-cents [:int {:min 1 :max 100000000}]]
     [:customer-email [:string {:min 3 :max 254}]]
     ;; Opaque, and deliberately so. Ordering does not know what a payment
     ;; method is, Payments does not parse it, and only an adapter hands it to
     ;; anyone who does.
     [:payment-method [:string {:min 1 :max 100}]]]]])

(defn order-placed
  [message-id fact-id causation-id correlation-id
   order-id product-name quantity total-cents customer-email payment-method]
  {:message/id   message-id
   :message/type order-placed-type
   :metadata     {:causation-id causation-id
                  :correlation-id correlation-id}
   :payload      {:fact-id fact-id
                  :order-id order-id
                  :product-name product-name
                  :quantity quantity
                  :total-cents total-cents
                  :customer-email customer-email
                  :payment-method payment-method}})
