(ns lab25.system
  "One deployment, two modules, two database identities.

  This composition root may name both public module APIs. Neither module may
  name the other's implementation or database. Catalog and Ordering connect
  as different Postgres roles, so this is enforced below the source tree."
  (:require [lab25.catalog.api :as catalog]
            [lab25.catalog.contract :as catalog-contract]
            [lab25.ordering.api :as ordering]
            [lab25.platform.bus :as bus]
            [next.jdbc :as jdbc]))

(defn start
  ([config] (start config {}))
  ([{catalog-config :catalog ordering-config :ordering} {:keys [new-id]
                                                         :or   {new-id random-uuid}}]
   (let [messages  (bus/bus)
         orders    (ordering/new-module (jdbc/get-datasource ordering-config))
         catalogue (catalog/new-module (jdbc/get-datasource catalog-config)
                                       {:new-id new-id})]
     (bus/subscribe! messages catalog-contract/price-changed-type
                     #(ordering/receive! orders %))
     {:catalog  catalogue
      :ordering orders
      :bus      messages})))

(defn relay-catalog!
  [{:keys [catalog bus]}]
  (catalog/relay! catalog #(bus/publish! bus %)))
