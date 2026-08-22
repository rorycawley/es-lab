(ns lab29.ordering.api
  "Ordering's public module API.

  The API is the only supported way into Ordering. Catalog publishes a public
  contract; the composition root delivers it to `receive!`."
  (:require [lab29.ordering.catalog-price-changed :as catalog-price-changed]
            [lab29.ordering.fulfilment :as fulfilment]
            [lab29.ordering.get-order :as get-order]
            [lab29.ordering.place-order :as place-order]
            [lab29.ordering.outbox :as outbox]
            [lab29.ordering.search-orders :as search-orders]
            [lab29.platform.behaviour :as behaviour]
            [lab29.platform.relay :as relay]))

(def contract
  {:module           :ordering
   :handles-commands #{}
   :consumes-events  #{:catalog/price-changed :payments/payment-succeeded}
   :publishes-events #{:ordering/order-placed}
   :provides-queries #{:ordering/get-order :ordering/search-orders}})

(defrecord Ordering [receive place-order get-order search relay dead-letters revive audit-log])

(defn new-module
  ([datasource] (new-module datasource {}))
  ([datasource {:keys [new-id] :or {new-id random-uuid}}]
   (let [audit      (atom [])
         context    {:datasource datasource :new-id new-id}
         settled    (behaviour/compose
                     #(fulfilment/handle! context %)
                     [(behaviour/telemetry
                       :ordering/payment-succeeded
                       {:kind       :consumer
                        :parent     :headers
                        :attributes (fn [{:keys [message]}]
                                      {:order-id (get-in message [:payload :order-id])})})
                      (behaviour/observation audit :ordering/payment-succeeded)
                      (behaviour/validation fulfilment/Request)])
         priced     (behaviour/compose
                     #(catalog-price-changed/handle! context %)
                     [(behaviour/telemetry
                       :ordering/catalog-price-changed
                      ;; The only place in the lab that adopts somebody else's
                      ;; trace. `:parent` reads the delivery's transport
                      ;; headers; the contract map itself is untouched.
                       {:kind       :consumer
                        :parent     :headers
                        :attributes (fn [{:keys [message]}]
                                      {:message-id (:message/id message)
                                       :fact-id    (get-in message [:payload :fact-id])})})
                      (behaviour/observation audit :ordering/catalog-price-changed)
                      (behaviour/validation catalog-price-changed/Request)])
         command    (behaviour/compose
                     #(place-order/handle! context %)
                    ;; Note what is absent: `:customer-email`. The order needs
                    ;; it, telemetry does not, and an allow-list is the only
                    ;; version of that decision that survives the next field.
                     [(behaviour/telemetry
                       :ordering/place-order
                       {:attributes #(select-keys % [:order-id :correlation-id
                                                     :product-id :quantity])})
                      (behaviour/observation audit :ordering/place-order)
                      (behaviour/validation place-order/Request)])
         search     (behaviour/compose
                     #(search-orders/handle context %)
                     [(behaviour/telemetry
                       :ordering/search-orders
                       {:attributes (fn [{:keys [query]}] {:query-length (count query)})})
                      (behaviour/observation audit :ordering/search-orders)
                      (behaviour/validation search-orders/Request)])
         query      (behaviour/compose
                     #(get-order/handle context %)
                     [(behaviour/telemetry
                       :ordering/get-order
                       {:attributes #(select-keys % [:order-id])})
                      (behaviour/observation audit :ordering/get-order)
                      (behaviour/validation get-order/Request)])
         relay      (fn [dispatcher]
                      (relay/drain! dispatcher datasource outbox/schema))]
     (->Ordering (fn [{:keys [message] :as delivery}]
                  ;; One module, two subscriptions. The dispatcher routes to a
                  ;; module; deciding which of its consumers wants a message is
                  ;; the module's own business, and doing it here keeps the
                  ;; routing table about modules rather than about functions.
                   (case (:event/type message)
                     :catalog/price-changed      (priced delivery)
                     :payments/payment-succeeded (settled delivery)))
                 command query search relay #(outbox/dead-letters context)
                 #(outbox/revive! context %1 %2) #(deref audit)))))

(defn receive!
  "Handle one delivery: `{:headers … :message …}`."
  [ordering delivery]
  ((:receive ordering) delivery))

(defn place-order! [ordering request]
  ((:place-order ordering) request))

(defn get-order [ordering request]
  ((:get-order ordering) request))

(defn search
  "Find orders by free text over the product ordered. Never over the customer."
  [ordering request]
  ((:search ordering) request))

(defn relay!
  "Drain this module's outbox through `dispatcher`."
  [ordering dispatcher]
  ((:relay ordering) dispatcher))

(defn dead-letters [ordering] ((:dead-letters ordering)))

(defn revive! [ordering message-id consumer] ((:revive ordering) message-id consumer))

(defn audit-log [ordering]
  ((:audit-log ordering)))
