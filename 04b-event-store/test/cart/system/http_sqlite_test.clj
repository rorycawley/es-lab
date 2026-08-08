(ns cart.system.http-sqlite-test
  "Full-stack HTTP smoke over SQLite.

   This proves the Component wiring can use the SQLite driven adapter and that
   a file-backed SQLite store survives service restart."
  (:require [cart.system :as system]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [matcher-combinators.test :refer [match?]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [org.eclipse.jetty.server ServerConnector]))

(def now 1735689600000)

(defn- sqlite-jdbc-url []
  (str "jdbc:sqlite:target/sqlite-http-test/" (random-uuid) ".sqlite3"))

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

(defn- sqlite-system [jdbc-url]
  (system/new-system {:store :sqlite
                      :db    {:jdbc-url jdbc-url
                              :pool-size 4
                              :busy-timeout-ms 10000}
                      :http  {:port 0}
                      :retry {:min-timeout 1}
                      :clock (constantly now)}))

(defn- with-started-system [jdbc-url f]
  (let [started (component/start-system (sqlite-system jdbc-url))]
    (try
      (f started)
      (finally
        (component/stop-system started)))))

(defn- add-item-task [cart-id product-id]
  {"cart-id" cart-id
   "product-item" {"product-id" product-id
                   "quantity" 2
                   "unit-price" 1299}})

(deftest http-service-persists-through-sqlite
  (let [client     (HttpClient/newHttpClient)
        jdbc-url   (sqlite-jdbc-url)
        cart-id    (str "sqlite-http-English-购物车-عربة-" (random-uuid))
        product-id "tea-茶-شاي"
        stream-id  (str "shopping_cart-" cart-id)]
    (testing "first service instance writes through the HTTP command path"
      (with-started-system
        jdbc-url
        (fn [started]
          (let [health (get! client started "/health")]
            (is (= 200 (.statusCode health)))
            (is (json-response? health))
            (is (match? {"status" "ok"} (response-body health))))

          (let [created (post-json! client
                                    started
                                    "/commands/add-product-item"
                                    (add-item-task cart-id product-id))]
            (is (= 201 (.statusCode created)))
            (is (json-response? created))
            (is (match? {"cart-id" cart-id
                         "stream-id" stream-id
                         "version" 1
                         "created-new-stream?" true}
                        (response-body created)))))))

    (testing "a new service instance reads the same cart through the query path"
      (with-started-system
        jdbc-url
        (fn [started]
          (let [summary (post-json! client
                                    started
                                    "/queries/get-cart"
                                    {"cart-id" cart-id})]
            (is (= 200 (.statusCode summary)))
            (is (json-response? summary))
            (is (match? {"cart-id" cart-id
                         "stream-id" stream-id
                         "exists?" true
                         "version" 1
                         "state" {"status" "opened"
                                  "product-items" {product-id 2}}}
                        (response-body summary)))))))))
