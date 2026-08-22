(ns lab26.catalog.api
  "Catalog's public module API.

  Other modules may call these functions or consume `catalog.contract`. They
  may not require the slice namespaces or read Catalog's tables."
  (:require [lab26.catalog.change-price :as change-price]
            [lab26.catalog.get-product :as get-product]
            [lab26.catalog.outbox :as outbox]
            [lab26.platform.behaviour :as behaviour]
            [lab26.platform.telemetry :as telemetry]))

(defrecord Catalog [change-price get-product relay audit-log])

(defn- publish-one!
  "Publish one pending delivery inside a producer span.

  `:parent` is the trace context frozen into the outbox row when the price
  actually changed, so this publish belongs to the request that caused it and
  not to whenever the relay happened to wake up. The headers handed onward are
  minted *here*, inside the producer span, so the consumer's span hangs off the
  publish rather than off the command."
  [context publish! {:keys [headers message]}]
  (telemetry/observe
   {:name       :catalog/publish-price-changed
    :kind       :producer
    :parent     headers
    :attributes {:message-id (:message/id message)
                 :fact-id    (get-in message [:payload :fact-id])}}
   (fn []
     (let [delivery  {:headers (telemetry/trace-headers) :message message}
           published (publish! delivery)]
       (outbox/mark-published! context (:message/id message))
       {:message message :headers (:headers delivery) :published published}))))

(defn new-module
  ([datasource] (new-module datasource {}))
  ([datasource {:keys [new-id]
                :or   {new-id random-uuid}}]
   (let [audit      (atom [])
         context    {:datasource datasource :new-id new-id}
         command    (behaviour/compose
                     #(change-price/handle! context %)
                     [(behaviour/telemetry
                       :catalog/change-price
                       {:attributes (fn [request]
                                      (select-keys request [:command-id :correlation-id
                                                            :product-id :price-cents]))})
                      (behaviour/observation audit :catalog/change-price)
                      (behaviour/validation change-price/Request)])
         query      (behaviour/compose
                     #(get-product/handle context %)
                     [(behaviour/telemetry
                       :catalog/get-product
                       {:attributes #(select-keys % [:product-id])})
                      (behaviour/observation audit :catalog/get-product)
                      (behaviour/validation get-product/Request)])
         relay      (fn [publish!]
                      (mapv #(publish-one! context publish! %)
                            (outbox/pending context)))]
     (->Catalog command query relay #(deref audit)))))

(defn change-price! [catalog request]
  ((:change-price catalog) request))

(defn get-product [catalog request]
  ((:get-product catalog) request))

(defn relay!
  "Publish pending Catalog messages and mark each only after delivery returns."
  [catalog publish!]
  ((:relay catalog) publish!))

(defn audit-log [catalog]
  ((:audit-log catalog)))
