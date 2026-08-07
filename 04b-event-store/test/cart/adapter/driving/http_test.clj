(ns cart.adapter.driving.http-test
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.adapter.driving.http :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def now 1735689600000)

(defn- new-handler []
  (http/handler {:event-store (memory/make-store)
                 :clock       (constantly now)
                 :retry       {:min-timeout 1}}))

(defn- body-stream [body]
  (ByteArrayInputStream.
   (.getBytes (json/generate-string body) StandardCharsets/UTF_8)))

(defn- request
  ([method uri]
   (request method uri nil))
  ([method uri body]
   (let [[path query-string] (str/split uri #"\?" 2)]
     (cond-> {:request-method method
              :uri            path}
       query-string (assoc :query-string query-string)
       body (assoc :headers {"content-type" "application/json"}
                   :body (body-stream body))))))

(defn- response-body [response]
  (json/parse-string (:body response)))

(def add-item-command
  {"type" "cart.command/add-product-item"
   "data" {"product-item" {"product-id" "sku-1"
                           "quantity" 2
                           "unit-price" 1299}}})

(def confirm-command
  {"type" "cart.command/confirm"
   "data" {}})

(deftest health-check
  (let [response ((new-handler) (request :get "/health"))]
    (is (= 200 (:status response)))
    (is (= {"status" "ok"} (response-body response)))))

(deftest command-post-appends-and-get-reads-cart
  (let [handler (new-handler)]
    (testing "POST /commands appends through cart.app.handle"
      (let [response (handler (request :post "/carts/c1/commands" add-item-command))
            body     (response-body response)]
        (is (= 201 (:status response)))
        (is (= "c1" (get body "cart-id")))
        (is (= "shopping_cart-c1" (get body "stream-id")))
        (is (= 1 (get body "version")))
        (is (true? (get body "created-new-stream?")))
        (is (= ["cart.event/product-item-added"]
               (mapv #(get % "type") (get body "events"))))
        (is (= [{"now" now}]
               (mapv #(get % "metadata") (get body "events"))))))

    (testing "GET /carts/:cart-id folds the current state without a projection"
      (let [response (handler (request :get "/carts/c1"))
            body     (response-body response)]
        (is (= 200 (:status response)))
        (is (true? (get body "exists?")))
        (is (= 1 (get body "version")))
        (is (= "opened" (get-in body ["state" "status"])))
        (is (= 2 (get-in body ["state" "product-items" "sku-1"])))))

    (testing "GET /events exposes the stream read contract"
      (let [response (handler (request :get "/carts/c1/events"))
            body     (response-body response)]
        (is (= 200 (:status response)))
        (is (= 1 (get body "version")))
        (is (= ["cart.event/product-item-added"]
               (mapv #(get % "type") (get body "events"))))))))

(deftest business-errors-return-422-and-do-not-write
  (let [handler  (new-handler)
        response (handler (request :post "/carts/c1/commands" confirm-command))
        body     (response-body response)]
    (is (= 422 (:status response)))
    (is (= "command-rejected" (get body "error")))
    (is (= "not-opened" (get body "reason")))
    (is (= 0 (get (response-body (handler (request :get "/carts/c1/events")))
                  "version")))))

(deftest stale-expected-version-returns-conflict
  (let [handler (new-handler)]
    (handler (request :post "/carts/c1/commands" add-item-command))
    (let [response (handler (request :post
                                     "/carts/c1/commands?expected-version=0"
                                     add-item-command))
          body     (response-body response)]
      (is (= 409 (:status response)))
      (is (= "version-conflict" (get body "error")))
      (is (= 0 (get body "expected")))
      (is (= 1 (get body "current"))))))

(deftest explicit-any-expected-version-appends-without-conflict
  (let [handler (new-handler)]
    (handler (request :post "/carts/c1/commands" add-item-command))
    (let [response (handler (request :post
                                     "/carts/c1/commands?expected-version=any"
                                     add-item-command))
          body     (response-body response)]
      (is (= 200 (:status response)))
      (is (= 2 (get body "version"))))))

(deftest rejects-invalid-http-input
  (let [handler (new-handler)]
    (testing "malformed JSON"
      (let [response (handler {:request-method :post
                               :uri            "/carts/c1/commands"
                               :headers        {"content-type" "application/json"}
                               :body           (ByteArrayInputStream.
                                                (.getBytes "{\"type\""
                                                           StandardCharsets/UTF_8))})]
        (is (= 400 (:status response)))
        (is (= "invalid-json" (get (response-body response) "error")))))

    (testing "bad expected version"
      (let [response (handler (request :post
                                       "/carts/c1/commands?expected-version=banana"
                                       add-item-command))]
        (is (= 400 (:status response)))
        (is (= "invalid-expected-version" (get (response-body response) "error")))))

    (testing "unknown command fails schema validation before reaching app code"
      (let [response (handler (request :post
                                       "/carts/c1/commands"
                                       {"type" "cart.command/unknown"
                                        "data" {}}))]
        (is (= 400 (:status response)))
        (is (= "invalid-command" (get (response-body response) "error")))))

    (testing "route cart-id must match body cart-id when supplied"
      (let [response (handler (request :post
                                       "/carts/c1/commands"
                                       {"type" "cart.command/confirm"
                                        "data" {"cart-id" "other"}}))]
        (is (= 400 (:status response)))
        (is (= "cart-id-mismatch" (get (response-body response) "error")))))))
