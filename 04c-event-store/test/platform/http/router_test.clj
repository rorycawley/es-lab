(ns platform.http.router-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [platform.http.router :as router]))

(defn- request [handler method uri]
  (handler {:request-method method :uri uri}))

(defn- body [response]
  (json/parse-string (:body response) true))

(deftest operational-endpoints-have-stable-json-responses
  (let [handler (router/handler)]
    (is (= {:status "ok"}
           (body (request handler :get "/health"))))
    (is (= {:status "ready"}
           (body (request handler :get "/ready"))))
    (is (= "application/json; charset=utf-8"
           (get-in (request handler :get "/health")
                   [:headers "content-type"])))))

(deftest readiness-can-refuse-traffic
  (let [response (request (router/handler {:ready? (constantly false)})
                          :get
                          "/ready")]
    (is (= 503 (:status response)))
    (is (= {:status "not-ready"} (body response)))))

(deftest business-routes-are-not-exposed-before-their-slices
  (let [response (request (router/handler)
                          :post
                          "/commands/add-product-item")]
    (is (= 404 (:status response)))
    (is (= {:outcome "invalid" :code "route-not-found"}
           (body response)))))

(deftest default-routing-errors-are-json
  (let [handler (router/handler)]
    (testing "unknown route"
      (is (= 404 (:status (request handler :get "/missing")))))
    (testing "known route with unsupported method"
      (is (= 405 (:status (request handler :post "/health")))))))
