(ns lab25.demo
  (:gen-class)
  (:require [lab25.catalog.api :as catalog]
            [lab25.ordering.api :as ordering]
            [lab25.postgres :as postgres]
            [lab25.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000025")
(def order-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")
(def order-2 #uuid "0f1c2b3a-0000-4000-8000-000000000102")

(defn- money [cents]
  (format "€%.2f" (/ cents 100.0)))

(defn- change-price! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- place-order! [ordering order-id quantity]
  (ordering/place-order! ordering {:order-id order-id
                                   :correlation-id (random-uuid)
                                   :product-id vanilla
                                   :quantity quantity}))

(defn -main [& _]
  (postgres/truncate!)
  (let [{:keys [catalog ordering] :as app} (system/start (postgres/config))]
    (println)
    (println "  One deployment. Two modules. Two database owners.")
    (println "  ──────────────────────────────────────────────────────────────")

    (println "  1. Catalog changes today's vanilla price to €3.00.")
    (change-price! catalog 300)
    (println "     Catalog committed its command, product and outbox together.")

    (println)
    (println "  2. Ordering cannot peek at Catalog's table.")
    (println "    " (:because (place-order! ordering order-1 2)))

    (println)
    (println "  3. The outbox publishes a public contract; Ordering copies it.")
    (println "     messages delivered:" (count (system/relay-catalog! app)))
    (let [placed (:accepted (place-order! ordering order-1 2))]
      (println "     order 1:" (money (:unit-price-cents placed)) "each,"
               (money (:total-cents placed)) "total"))

    (println)
    (println "  4. Catalog changes today's price to €4.50.")
    (change-price! catalog 450)
    (system/relay-catalog! app)
    (let [old-order (:found (ordering/get-order ordering {:order-id order-1}))
          new-order (:accepted (place-order! ordering order-2 1))
          product   (:found (catalog/get-product catalog {:product-id vanilla}))]
      (println "     Catalog current price:" (money (:current-price-cents product)))
      (println "     old order still says:" (money (:unit-price-cents old-order)))
      (println "     new order says:" (money (:unit-price-cents new-order))))

    (println)
    (println "  The duplicate prices are not duplication to remove.")
    (println "  One means now; the other means agreed then.")
    (println "  ──────────────────────────────────────────────────────────────")
    (println)))
