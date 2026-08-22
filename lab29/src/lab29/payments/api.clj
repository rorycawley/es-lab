(ns lab29.payments.api
  "Payments' public module API.

  The gateway is injected, never constructed here. Which provider is taking the
  money is a deployment decision made in `system.clj`, and this namespace does
  not know the answer -- it only knows the port."
  (:require [lab29.payments.charge-order :as charge-order]
            [lab29.payments.get-payment :as get-payment]
            [lab29.payments.outbox :as outbox]
            [lab29.payments.port :as port]
            [lab29.payments.webhook :as webhook]
            [lab29.platform.behaviour :as behaviour]
            [lab29.platform.relay :as relay]))

(def contract
  {:module           :payments
   :handles-commands #{:payments/charge-order}
   :consumes-events  #{}
   :publishes-events #{:payments/payment-succeeded}
   :provides-queries #{:payments/get-payment}})

(defrecord Payments [charge callback get-payment relay dead-letters revive provider audit-log])

(defn new-module
  ([datasource gateway] (new-module datasource gateway {}))
  ([datasource gateway {:keys [new-id] :or {new-id random-uuid}}]
   (let [audit    (atom [])
         context  {:datasource datasource :gateway gateway :new-id new-id}
         charge   (behaviour/compose
                   #(charge-order/handle! context %)
                   [(behaviour/telemetry
                     :payments/charge-order
                     {:kind       :consumer
                      :parent     :headers
                      :attributes (fn [{:keys [message]}]
                                    {:fact-id  (get-in message [:payload :fact-id])
                                     :order-id (get-in message [:payload :order-id])
                                     :provider (port/provider-name gateway)})})
                    (behaviour/observation audit :payments/charge-order)
                    (behaviour/validation charge-order/Request)])
         ;; The callback is already translated and verified by the time it
         ;; arrives here. No schema wraps it, because its shape is this
         ;; module's own and the adapter is the thing that had to be careful.
         callback (behaviour/compose
                   #(webhook/handle! context %)
                   [(behaviour/telemetry
                     :payments/provider-callback
                     {:kind       :consumer
                      :attributes (fn [event]
                                    {:provider   (:provider event)
                                     :event-type (:event-type event)})})
                    (behaviour/observation audit :payments/provider-callback)])
         query    (behaviour/compose
                   #(get-payment/handle context %)
                   [(behaviour/telemetry
                     :payments/get-payment
                     {:attributes #(select-keys % [:order-id])})
                    (behaviour/observation audit :payments/get-payment)
                    (behaviour/validation get-payment/Request)])
         relay    (fn [dispatcher]
                    (relay/drain! dispatcher datasource outbox/schema))]
     (->Payments charge callback query relay #(outbox/dead-letters context)
                 #(outbox/revive! context %1 %2)
                 (port/provider-name gateway) #(deref audit)))))

(defn charge! [payments delivery] ((:charge payments) delivery))
(defn callback! [payments event] ((:callback payments) event))
(defn get-payment [payments request] ((:get-payment payments) request))
(defn relay!
  "Drain this module's outbox through `dispatcher`."
  [payments dispatcher]
  ((:relay payments) dispatcher))

(defn dead-letters
  "Messages this module gave up on, and why."
  [payments]
  ((:dead-letters payments)))

(defn revive!
  "Put one consumer's dead letter back on the queue."
  [payments message-id consumer]
  ((:revive payments) message-id consumer))
(defn provider [payments] (:provider payments))
(defn audit-log [payments] ((:audit-log payments)))
