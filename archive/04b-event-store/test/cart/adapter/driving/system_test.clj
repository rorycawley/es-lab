(ns cart.adapter.driving.system-test
  (:require [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.system :as system]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [matcher-combinators.test :refer [match?]]
            [ring.adapter.jetty :as jetty])
  (:import [java.io Closeable]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [org.eclipse.jetty.server ServerConnector]))

(definterface Stoppable
  (stop []))

(defn- bound-port [started-system]
  (let [server    (get-in started-system [:http-server :server])
        connector (first (.getConnectors server))]
    (.getLocalPort ^ServerConnector connector)))

(defn- endpoint [started-system path]
  (URI/create (str "http://localhost:" (bound-port started-system) path)))

(defn- request-builder [started-system path]
  (-> (HttpRequest/newBuilder (endpoint started-system path))
      (.header "accept" "application/json")))

(defn- get! [client started-system path]
  (.send client
         (-> (request-builder started-system path)
             (.GET)
             (.build))
         (HttpResponse$BodyHandlers/ofString)))

(defn- post-json! [client started-system path body]
  (.send client
         (-> (request-builder started-system path)
             (.header "content-type" "application/json")
             (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
             (.build))
         (HttpResponse$BodyHandlers/ofString)))

(defn- response-body [response]
  (json/parse-string (.body response)))

(defn- json-response? [response]
  (some-> (.headers response)
          (.firstValue "content-type")
          (.orElse "")
          (str/starts-with? "application/json")))

(deftest memory-system-starts-and-stops-http-server
  (let [stopped? (atom false)]
    (with-redefs [jetty/run-jetty (fn [_ _]
                                    (reify Stoppable
                                      (stop [_] (reset! stopped? true))))]
      (let [started (component/start-system
                     (system/new-system {:store :memory
                                         :http  {:port 0}}))]
        (try
          (is (some? (get-in started [:event-store :store])))
          (is (some? (get-in started [:cart-command :handler])))
          (is (some? (get-in started [:cart-query :query])))
          (is (some? (get-in started [:http-server :server])))
          (is (fn? (get-in started [:http-server :handler])))
          (finally
            (component/stop-system started))))
      (is (true? @stopped?)))))

(deftest memory-system-serves-real-http-requests
  (let [started (component/start-system
                 (system/new-system {:store :memory
                                     :http  {:port 0}
                                     :retry {:min-timeout 1}
                                     :clock (constantly 1735689600000)}))
        client  (HttpClient/newHttpClient)
        cart-id (str "system-" (random-uuid))]
    (try
      (let [response (get! client started "/health")]
        (is (= 200 (.statusCode response)))
        (is (json-response? response))
        (is (match? {"status" "ok"} (response-body response))))

      (let [response (post-json! client
                                 started
                                 "/commands/add-product-item"
                                 {"cart-id" cart-id
                                  "product-item" {"product-id" "sku-1"
                                                  "quantity" 2
                                                  "unit-price" 1299}})
            body     (response-body response)]
        (is (= 201 (.statusCode response)))
        (is (json-response? response))
        (is (match? {"cart-id" cart-id
                     "stream-id" (str "shopping_cart-" cart-id)
                     "version" 1
                     "created-new-stream?" true
                     "events" [{"type" "cart.event/product-item-added"
                                "data" {"cart-id" cart-id
                                        "added-at" 1735689600000
                                        "product-item" {"product-id" "sku-1"
                                                        "quantity" 2
                                                        "unit-price" 1299}}
                                "metadata" {"now" 1735689600000}}]}
                    body)))

      (let [response (post-json! client
                                 started
                                 "/queries/get-cart"
                                 {"cart-id" cart-id})
            body     (response-body response)]
        (is (= 200 (.statusCode response)))
        (is (json-response? response))
        (is (match? {"cart-id" cart-id
                     "stream-id" (str "shopping_cart-" cart-id)
                     "exists?" true
                     "version" 1
                     "state" {"status" "opened"
                              "product-items" {"sku-1" 2}}}
                    body)))

      (let [response (get! client started "/carts/c1")]
        (is (= 404 (.statusCode response)))
        (is (match? {"error" "not-found"} (response-body response))))
      (finally
        ;; Close before stopping the server: an HttpClient left open holds a
        ;; selector thread and a pooled connection per test, and this suite
        ;; runs 100+ of them in one JVM.
        (.close client)
        (component/stop-system started)))))

(deftest postgres-database-component-does-not-run-migrations
  (let [closed?    (atom false)
        datasource (reify Closeable
                     (close [_] (reset! closed? true)))]
    (with-redefs [postgres/make-datasource (fn [_] datasource)]
      (let [started (component/start (system/map->Database {:config {}}))]
        (try
          (is (identical? datasource (:datasource started)))
          (finally
            (component/stop started))))
      (is (true? @closed?)))))
