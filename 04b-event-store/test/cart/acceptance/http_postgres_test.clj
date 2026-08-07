(ns cart.acceptance.http-postgres-test
  "Full-stack HTTP smoke over real Postgres.

   This is intentionally thin. The domain, SQL function, Postgres adapter and
   HTTP contract each have deeper focused tests; this one proves the Component
   wiring works when Jetty, command/query use cases, the runtime DB role and
   migrated Postgres are all in the path."
  (:require [cart.system :as system]
            [cart.test-db :as db]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [matcher-combinators.test :refer [match?]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [org.eclipse.jetty.server ServerConnector]))

(use-fixtures :once db/with-postgres)
(use-fixtures :each (fn [f] (db/truncate! db/*datasource*) (f)))

(def now 1735689600000)

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

(defn- postgres-system []
  (system/new-system {:store :postgres
                      :db    {:jdbc-url  db/*jdbc-url*
                              :username  db/app-username
                              :password  db/app-password
                              :pool-size 2}
                      :http  {:port 0}
                      :retry {:min-timeout 1}
                      :clock (constantly now)}))

(defn- with-started-system [f]
  (let [started (component/start-system (postgres-system))]
    (try
      (f started)
      (finally
        (component/stop-system started)))))

(defn- add-item-task [cart-id]
  {"cart-id" cart-id
   "product-item" {"product-id" "sku-1"
                   "quantity" 2
                   "unit-price" 1299}})

(deftest http-service-persists-through-postgres
  (let [client    (HttpClient/newHttpClient)
        cart-id   (str "pg-http-" (random-uuid))
        stream-id (str "shopping_cart-" cart-id)]
    (testing "first service instance writes through the HTTP command path"
      (with-started-system
        (fn [started]
          (let [health (get! client started "/health")]
            (is (= 200 (.statusCode health)))
            (is (json-response? health))
            (is (match? {"status" "ok"} (response-body health))))

          (let [created (post-json! client
                                    started
                                    "/commands/add-product-item"
                                    (add-item-task cart-id))]
            (is (= 201 (.statusCode created)))
            (is (json-response? created))
            (is (match? {"cart-id" cart-id
                         "stream-id" stream-id
                         "version" 1
                         "created-new-stream?" true}
                        (response-body created)))))))

    (testing "the write really landed in Postgres"
      (is (= 1 (db/count-messages db/*datasource* stream-id)))
      (is (= [1] (db/positions db/*datasource* stream-id))))

    (testing "a new service instance reads the same cart through the query path"
      (with-started-system
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
                                  "product-items" {"sku-1" 2}}}
                        (response-body summary)))))))))
