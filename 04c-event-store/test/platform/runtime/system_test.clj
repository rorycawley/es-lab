(ns platform.runtime.system-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [platform.runtime.system :as system]
            [ring.adapter.jetty :as jetty])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [org.eclipse.jetty.server ServerConnector]))

(definterface Stoppable
  (stop []))

(deftest system-starts-and-stops-the-http-component
  (let [stopped? (atom false)]
    (with-redefs [jetty/run-jetty
                  (fn [handler options]
                    (is (fn? handler))
                    (is (= {:host "127.0.0.1" :port 0 :join? false}
                           options))
                    (reify Stoppable
                      (stop [_] (reset! stopped? true))))]
      (let [running (component/start-system
                     (system/new-system
                      {:http {:host "127.0.0.1" :port 0}}))]
        (try
          (is (some? (get-in running [:http-server :server])))
          (is (fn? (get-in running [:http-server :handler])))
          (finally
            (component/stop-system running)))))
    (is (true? @stopped?))))

(defn- bound-port [running]
  (let [server    (get-in running [:http-server :server])
        connector (first (.getConnectors server))]
    (.getLocalPort ^ServerConnector connector)))

(defn- get! [client port path]
  (.send client
         (-> (HttpRequest/newBuilder
              (URI/create (str "http://127.0.0.1:" port path)))
             (.header "accept" "application/json")
             (.GET)
             (.build))
         (HttpResponse$BodyHandlers/ofString)))

(defn- post-json! [client port path body]
  (.send client
         (-> (HttpRequest/newBuilder
              (URI/create (str "http://127.0.0.1:" port path)))
             (.header "accept" "application/json")
             (.header "content-type" "application/json")
             (.POST (HttpRequest$BodyPublishers/ofString
                     (json/generate-string body)))
             (.build))
         (HttpResponse$BodyHandlers/ofString)))

(deftest real-jetty-serves-the-operational-contract
  (let [running (component/start-system
                 (system/new-system
                  {:http {:host "127.0.0.1" :port 0}}))
        port    (bound-port running)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (doseq [[path expected]
                [["/health" {"status" "ok"}]
                 ["/ready" {"status" "ready"}]]]
          (testing path
            (let [response (get! client port path)]
              (is (= 200 (.statusCode response)))
              (is (= expected (json/parse-string (.body response)))))))

        (testing "/openapi.json"
          (let [response (get! client port "/openapi.json")]
            (is (= 200 (.statusCode response)))
            (is (= "3.1.0"
                   (get (json/parse-string (.body response)) "openapi")))))

        (testing "first add then projected view"
          (let [added-response
                (post-json!
                 client port "/commands/add-product-item"
                 {"request-id" "30000000-0000-0000-0000-000000000001"
                  "product-item"
                  {"product-id" "20000000-0000-0000-0000-000000000001"
                   "quantity" 2}})
                added (json/parse-string (.body added-response))
                viewed-response
                (post-json! client port "/queries/view-cart"
                            {"cart-id" (get-in added ["result" "cart-id"])})
                viewed (json/parse-string (.body viewed-response))]
            (is (= 200 (.statusCode added-response)))
            (is (= 200 (.statusCode viewed-response)))
            (is (= (select-keys (get added "result")
                                ["cart-id" "status" "items"])
                   (select-keys (get viewed "result")
                                ["cart-id" "status" "items"]))))))
      (finally
        (component/stop-system running)))))
