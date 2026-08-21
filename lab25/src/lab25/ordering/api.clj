(ns lab25.ordering.api
  "Ordering's public module API.

  The API is the only supported way into Ordering. Catalog publishes a public
  contract; the composition root delivers it to `receive!`."
  (:require [lab25.ordering.catalog-price-changed :as catalog-price-changed]
            [lab25.ordering.get-order :as get-order]
            [lab25.ordering.place-order :as place-order]
            [lab25.platform.behaviour :as behaviour]))

(defrecord Ordering [datasource audit receive place-order get-order])

(defn new-module
  [datasource]
  (let [audit      (atom [])
        context    {:datasource datasource}
        receive    (behaviour/compose
                    #(catalog-price-changed/handle! context %)
                    [(behaviour/observation audit :ordering/catalog-price-changed)
                     (behaviour/validation catalog-price-changed/Request)])
        command    (behaviour/compose
                    #(place-order/handle! context %)
                    [(behaviour/observation audit :ordering/place-order)
                     (behaviour/validation place-order/Request)])
        query      (behaviour/compose
                    #(get-order/handle context %)
                    [(behaviour/observation audit :ordering/get-order)
                     (behaviour/validation get-order/Request)])]
    (->Ordering datasource audit receive command query)))

(defn receive! [ordering message]
  ((:receive ordering) message))

(defn place-order! [ordering request]
  ((:place-order ordering) request))

(defn get-order [ordering request]
  ((:get-order ordering) request))

(defn audit-log [ordering]
  @(:audit ordering))
