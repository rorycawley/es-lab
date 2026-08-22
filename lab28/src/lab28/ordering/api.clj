(ns lab28.ordering.api
  "Ordering's public module API.

  The API is the only supported way into Ordering. Catalog publishes a public
  contract; the composition root delivers it to `receive!`."
  (:require [lab28.ordering.catalog-price-changed :as catalog-price-changed]
            [lab28.ordering.get-order :as get-order]
            [lab28.ordering.place-order :as place-order]
            [lab28.ordering.outbox :as outbox]
            [lab28.ordering.search-orders :as search-orders]
            [lab28.platform.behaviour :as behaviour]
            [lab28.platform.relay :as relay]))

(defrecord Ordering [receive place-order get-order search relay dead-letters revive audit-log])

(defn new-module
  ([datasource] (new-module datasource {}))
  ([datasource {:keys [new-id] :or {new-id random-uuid}}]
   (let [audit      (atom [])
         context    {:datasource datasource :new-id new-id}
         receive    (behaviour/compose
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
         relay      (fn [publish!]
                      (relay/drain! {:pending         #(outbox/pending context)
                                     :mark-published! #(outbox/mark-published! context %)
                                     :record-failure! #(outbox/record-failure! context %1 %2)
                                     :dead-letter!    #(outbox/dead-letter! context %1 %2 %3)
                                     :publish!        publish!}))]
     (->Ordering receive command query search relay #(outbox/dead-letters context)
                 #(outbox/revive! context %) #(deref audit)))))

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
  "Drain the outbox. Returns `{:published [...] :failed [...] :dead-lettered [...]}`."
  [ordering publish!]
  ((:relay ordering) publish!))

(defn dead-letters [ordering] ((:dead-letters ordering)))

(defn revive! [ordering message-id] ((:revive ordering) message-id))

(defn audit-log [ordering]
  ((:audit-log ordering)))
