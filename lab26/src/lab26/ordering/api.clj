(ns lab26.ordering.api
  "Ordering's public module API.

  The API is the only supported way into Ordering. Catalog publishes a public
  contract; the composition root delivers it to `receive!`."
  (:require [lab26.ordering.catalog-price-changed :as catalog-price-changed]
            [lab26.ordering.get-order :as get-order]
            [lab26.ordering.place-order :as place-order]
            [lab26.platform.behaviour :as behaviour]))

(defrecord Ordering [receive place-order get-order audit-log])

(defn new-module
  [datasource]
  (let [audit      (atom [])
        context    {:datasource datasource}
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
        query      (behaviour/compose
                    #(get-order/handle context %)
                    [(behaviour/telemetry
                      :ordering/get-order
                      {:attributes #(select-keys % [:order-id])})
                     (behaviour/observation audit :ordering/get-order)
                     (behaviour/validation get-order/Request)])]
    (->Ordering receive command query #(deref audit))))

(defn receive!
  "Handle one delivery: `{:headers … :message …}`."
  [ordering delivery]
  ((:receive ordering) delivery))

(defn place-order! [ordering request]
  ((:place-order ordering) request))

(defn get-order [ordering request]
  ((:get-order ordering) request))

(defn audit-log [ordering]
  ((:audit-log ordering)))
