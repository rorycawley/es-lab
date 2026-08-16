(ns platform.runtime.system
  "Composition root for the cart modular monolith."
  (:require [cart.adapter.out.persistence.memory :as memory]
            [cart.adapter.out.persistence.postgres :as postgres]
            [cart.adapter.out.persistence.sqlite :as sqlite]
            [cart.slice.add-product-item.adapter.in.http :as add-http]
            [cart.slice.add-product-item.handler :as add-handler]
            [cart.slice.view-cart.adapter.in.http :as view-http]
            [cart.slice.view-cart.handler :as view-handler]
            [com.stuartsierra.component :as component]
            [platform.http.router :as http]
            [platform.persistence.datasource :as datasource]
            [ring.adapter.jetty :as jetty]))

(defrecord Persistence [store config datasource adapter]
  component/Lifecycle
  (start [this]
    (if adapter
      this
      (case store
        :memory (assoc this :adapter (memory/new-store))
        :sqlite (let [datasource (datasource/sqlite-datasource config)]
                  (assoc this
                         :datasource datasource
                         :adapter (sqlite/new-store datasource)))
        :postgres (let [datasource (datasource/postgres-datasource config)]
                    (assoc this
                           :datasource datasource
                           :adapter (postgres/new-store datasource))))))
  (stop [this]
    (datasource/close! datasource)
    (assoc this :datasource nil :adapter nil)))

(defrecord CartApplication [persistence key-ring uuid-fn clock
                            add-product-item view-cart]
  component/Lifecycle
  (start [this]
    (if add-product-item
      this
      (let [store (:adapter persistence)]
        (assoc this
               :add-product-item
               (add-handler/new-handler
                {:event-store store
                 :idempotency-store store
                 :unit-of-work store
                 :key-ring key-ring
                 :uuid-fn uuid-fn
                 :clock clock})
               :view-cart
               (view-handler/new-handler {:projection-store store
                                          :key-ring key-ring})))))
  (stop [this]
    (assoc this :add-product-item nil :view-cart nil)))

(defrecord HttpServer [config cart-application server handler]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [handler (http/handler
                     {:ready? (constantly true)
                      :add-product-item
                      (add-http/handler (:add-product-item cart-application))
                      :view-cart
                      (view-http/handler (:view-cart cart-application))})
            server  (jetty/run-jetty handler
                                     {:host  (:host config)
                                      :port  (:port config)
                                      :join? false})]
        (assoc this :handler handler :server server))))
  (stop [this]
    (when server
      (.stop server))
    (assoc this :handler nil :server nil)))

(defn new-system
  [{:keys [store http db observation uuid-fn clock]
    :or {store :memory
         observation {:active-key-id "primary"
                      :keys {"primary" "system-test-observation-signing-key"}}
         uuid-fn #(java.util.UUID/randomUUID)
         clock #(java.time.Instant/now)}}]
  (component/system-map
   :persistence (map->Persistence {:store store :config db})
   :cart-application
   (component/using
    (map->CartApplication {:key-ring observation
                           :uuid-fn uuid-fn
                           :clock clock})
    [:persistence])
   :http-server
   (component/using (map->HttpServer {:config http}) [:cart-application])))
