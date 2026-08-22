(ns lab28.catalog.api
  "Catalog's public module API.

  Other modules may call these functions or consume `catalog.contract`. They
  may not require the slice namespaces or read Catalog's tables."
  (:require [lab28.catalog.change-price :as change-price]
            [lab28.catalog.describe-product :as describe-product]
            [lab28.catalog.get-product :as get-product]
            [lab28.catalog.outbox :as outbox]
            [lab28.catalog.search-products :as search-products]
            [lab28.platform.behaviour :as behaviour]
            [lab28.platform.relay :as relay]))

(defrecord Catalog [change-price describe-product get-product search relay
                    dead-letters revive audit-log])

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
         describe   (behaviour/compose
                     #(describe-product/handle! context %)
                     [(behaviour/telemetry
                       :catalog/describe-product
                       {:attributes (fn [request]
                                      (select-keys request [:command-id :correlation-id
                                                            :product-id]))})
                      (behaviour/observation audit :catalog/describe-product)
                      (behaviour/validation describe-product/Request)])
         search     (behaviour/compose
                     #(search-products/handle context %)
                     ;; The query string is deliberately not an attribute.
                     ;; Somebody will search for their own email address, and
                     ;; lab 26's allow-list is only an allow-list if free text
                     ;; cannot walk through it. Length and result count answer
                     ;; the operational questions without carrying the words.
                     [(behaviour/telemetry
                       :catalog/search-products
                       {:attributes (fn [{:keys [query]}]
                                      {:query-length (count query)})})
                      (behaviour/observation audit :catalog/search-products)
                      (behaviour/validation search-products/Request)])
         query      (behaviour/compose
                     #(get-product/handle context %)
                     [(behaviour/telemetry
                       :catalog/get-product
                       {:attributes #(select-keys % [:product-id])})
                      (behaviour/observation audit :catalog/get-product)
                      (behaviour/validation get-product/Request)])
         relay      (fn [publish!]
                      (relay/drain! {:pending         #(outbox/pending context)
                                     :mark-published! #(outbox/mark-published! context %)
                                     :record-failure! #(outbox/record-failure! context %1 %2)
                                     :dead-letter!    #(outbox/dead-letter! context %1 %2 %3)
                                     :publish!        publish!}))]
     (->Catalog command describe query search relay #(outbox/dead-letters context)
                #(outbox/revive! context %) #(deref audit)))))

(defn change-price! [catalog request]
  ((:change-price catalog) request))

(defn describe-product! [catalog request]
  ((:describe-product catalog) request))

(defn get-product [catalog request]
  ((:get-product catalog) request))

(defn search
  "Find products by free text. Returns `:found`, `:did-you-mean` or `:no-matches`."
  [catalog request]
  ((:search catalog) request))

(defn relay!
  "Drain the outbox. Returns `{:published [...] :failed [...] :dead-lettered [...]}`."
  [catalog publish!]
  ((:relay catalog) publish!))

(defn dead-letters [catalog] ((:dead-letters catalog)))

(defn revive! [catalog message-id] ((:revive catalog) message-id))

(defn audit-log [catalog]
  ((:audit-log catalog)))
