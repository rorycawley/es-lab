(ns lab29.catalog.api
  "Catalog's public module API.

  Other modules may call these functions or consume `catalog.contract`. They
  may not require the slice namespaces or read Catalog's tables."
  (:require [lab29.catalog.change-price :as change-price]
            [lab29.catalog.describe-product :as describe-product]
            [lab29.catalog.get-product :as get-product]
            [lab29.catalog.outbox :as outbox]
            [lab29.catalog.search-products :as search-products]
            [lab29.platform.behaviour :as behaviour]
            [lab29.platform.relay :as relay]))

(def contract
  "Catalog's public contract, as data. `system.clj` derives the routing table
  from these declarations, so this is the thing that is true rather than a
  description of it."
  {:module           :catalog
   :handles-commands #{}
   :consumes-events  #{}
   :publishes-events #{:catalog/price-changed :catalog/product-described}
   :provides-queries #{:catalog/get-product :catalog/search-products}})

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
         relay      (fn [dispatcher]
                      (relay/drain! dispatcher datasource outbox/schema))]
     (->Catalog command describe query search relay #(outbox/dead-letters context)
                #(outbox/revive! context %1 %2) #(deref audit)))))

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
  "Drain this module's outbox through `dispatcher`.
  Returns `{:delivered [...] :failed [...] :dead-lettered [...]}`."
  [catalog dispatcher]
  ((:relay catalog) dispatcher))

(defn dead-letters [catalog] ((:dead-letters catalog)))

(defn revive! [catalog message-id consumer] ((:revive catalog) message-id consumer))

(defn audit-log [catalog]
  ((:audit-log catalog)))
