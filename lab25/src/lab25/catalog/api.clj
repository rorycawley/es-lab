(ns lab25.catalog.api
  "Catalog's public module API.

  Other modules may call these functions or consume `catalog.contract`. They
  may not require the slice namespaces or read Catalog's tables."
  (:require [lab25.catalog.change-price :as change-price]
            [lab25.catalog.get-product :as get-product]
            [lab25.catalog.outbox :as outbox]
            [lab25.platform.behaviour :as behaviour]))

(defrecord Catalog [change-price get-product relay audit-log])

(defn new-module
  ([datasource] (new-module datasource {}))
  ([datasource {:keys [new-id]
                :or   {new-id random-uuid}}]
   (let [audit      (atom [])
         context    {:datasource datasource :new-id new-id}
         command    (behaviour/compose
                     #(change-price/handle! context %)
                     [(behaviour/observation audit :catalog/change-price)
                      (behaviour/validation change-price/Request)])
         query      (behaviour/compose
                     #(get-product/handle context %)
                     [(behaviour/observation audit :catalog/get-product)
                      (behaviour/validation get-product/Request)])
         relay      (fn [publish!]
                      (mapv (fn [message]
                              (let [delivery (publish! message)]
                                (outbox/mark-published! context (:message/id message))
                                {:message message :delivery delivery}))
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
