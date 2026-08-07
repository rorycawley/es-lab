(ns cart.adapter.driving.http-test
  (:require [cart.adapter.driven.event-store-memory :as memory]
            [cart.adapter.driving.http :as http]
            [cart.app.command :as app-command]
            [cart.app.query :as app-query]
            [cart.port.cart-command :as cart-command]
            [cart.port.cart-query :as cart-query]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def now 1735689600000)

(defn- new-handler []
  (let [event-store (memory/make-store)]
    (http/handler {:cart-command (app-command/make-event-store-command
                                  event-store
                                  {:min-timeout 1})
                   :cart-query   (app-query/make-event-store-query event-store)
                   :clock        (constantly now)})))

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

(defn- contract []
  (json/parse-string (slurp (io/resource "openapi/cart-api.openapi.json"))))

(defn- pointer-token [token]
  (-> token
      (str/replace "~1" "/")
      (str/replace "~0" "~")))

(defn- resolve-ref [openapi ref]
  (when-not (str/starts-with? ref "#/")
    (throw (ex-info "Only local OpenAPI refs are supported in tests"
                    {:ref ref})))
  (get-in openapi (mapv pointer-token (str/split (subs ref 2) #"/"))))

(defn- resolve-ref-node [openapi node]
  (if-let [ref (get node "$ref")]
    (resolve-ref openapi ref)
    node))

(declare schema-errors)

(defn- schema-path [path]
  (if (seq path)
    (str "$." (str/join "." path))
    "$"))

(defn- type-matches? [type value]
  (case type
    "object"  (map? value)
    "array"   (vector? value)
    "string"  (string? value)
    "integer" (integer? value)
    "number"  (number? value)
    "boolean" (or (true? value) (false? value))
    true))

(defn- type-errors [path schema value]
  (if-let [type (get schema "type")]
    (when-not (type-matches? type value)
      [(str (schema-path path) " must be " type)])
    []))

(defn- required-errors [path schema value]
  (if (map? value)
    (for [key (get schema "required" [])
          :when (not (contains? value key))]
      (str (schema-path (conj path key)) " is required"))
    []))

(defn- additional-property-errors [path schema value]
  (if (and (map? value) (false? (get schema "additionalProperties")))
    (let [allowed (set (keys (get schema "properties" {})))]
      (for [key (keys value)
            :when (not (contains? allowed key))]
        (str (schema-path (conj path key)) " is not allowed")))
    []))

(defn- property-errors [openapi path schema value]
  (if (map? value)
    (mapcat (fn [[key property-schema]]
              (when (contains? value key)
                (schema-errors openapi (conj path key) property-schema (get value key))))
            (get schema "properties" {}))
    []))

(defn- item-errors [openapi path schema value]
  (if (and (vector? value) (contains? schema "items"))
    (mapcat (fn [index item]
              (schema-errors openapi (conj path index) (get schema "items") item))
            (range)
            value)
    []))

(defn- minimum-errors [path schema value]
  (if (and (number? value)
           (contains? schema "minimum")
           (< value (get schema "minimum")))
    [(str (schema-path path) " must be >= " (get schema "minimum"))]
    []))

(defn- min-length-errors [path schema value]
  (if (and (string? value)
           (contains? schema "minLength")
           (< (count value) (get schema "minLength")))
    [(str (schema-path path) " length must be >= " (get schema "minLength"))]
    []))

(defn- const-errors [path schema value]
  (if (and (contains? schema "const") (not= value (get schema "const")))
    [(str (schema-path path) " must be " (pr-str (get schema "const")))]
    []))

(defn- enum-errors [path schema value]
  (if (and (contains? schema "enum")
           (not (some #{value} (get schema "enum"))))
    [(str (schema-path path) " must be one of " (pr-str (get schema "enum")))]
    []))

(defn- all-of-errors [openapi path schema value]
  (mapcat #(schema-errors openapi path % value) (get schema "allOf" [])))

(defn- one-of-errors [openapi path schema value]
  (if-let [schemas (seq (get schema "oneOf"))]
    (let [results (map #(schema-errors openapi path % value) schemas)
          matches (count (filter empty? results))]
      (when-not (= 1 matches)
        [(str (schema-path path) " must match exactly one oneOf schema")]))
    []))

(defn- schema-errors [openapi path schema value]
  (let [schema (resolve-ref-node openapi schema)]
    (vec
     (concat
      (all-of-errors openapi path schema value)
      (one-of-errors openapi path schema value)
      (type-errors path schema value)
      (required-errors path schema value)
      (additional-property-errors path schema value)
      (property-errors openapi path schema value)
      (item-errors openapi path schema value)
      (minimum-errors path schema value)
      (min-length-errors path schema value)
      (const-errors path schema value)
      (enum-errors path schema value)))))

(defn- request-schema [openapi path method]
  (get-in openapi ["paths" path method "requestBody" "content" "application/json" "schema"]))

(defn- response-schema [openapi path method status]
  (->> (get-in openapi ["paths" path method "responses" (str status)])
       (resolve-ref-node openapi)
       (#(get-in % ["content" "application/json" "schema"]))))

(defn- assert-request-contract [path method body]
  (let [openapi (contract)
        schema  (request-schema openapi path method)
        errors  (schema-errors openapi [] schema body)]
    (is (some? schema) (str "missing request schema for " method " " path))
    (is (= [] errors) (str method " " path " request: " (pr-str errors)))))

(defn- assert-response-contract [path method response]
  (let [openapi       (contract)
        content-type  (get-in response [:headers "content-type"])
        schema        (response-schema openapi path method (:status response))
        body          (response-body response)
        errors        (schema-errors openapi [] schema body)]
    (is (str/starts-with? content-type "application/json")
        (str method " " path " content-type was " (pr-str content-type)))
    (is (some? schema)
        (str "missing response schema for "
             method
             " "
             path
             " "
             (:status response)))
    (is (= [] errors)
        (str method " "
             path
             " "
             (:status response)
             " response: "
             (pr-str errors)))))

(def add-item-task
  {"cart-id" "c1"
   "product-item" {"product-id" "sku-1"
                   "quantity" 2
                   "unit-price" 1299}})

(def confirm-task
  {"cart-id" "c1"})

(def cart-query-request
  {"cart-id" "c1"})

(def unicode-cart-id "cart-English-购物车-عربة")
(def unicode-product-id "tea-茶-شاي")

(def unicode-add-item-task
  {"cart-id" unicode-cart-id
   "product-item" {"product-id" unicode-product-id
                   "quantity" 2
                   "unit-price" 1299}})

(def unicode-cart-query-request
  {"cart-id" unicode-cart-id})

(defn- add-item-task-for [cart-id]
  (assoc add-item-task "cart-id" cart-id))

(deftest health-check
  (let [response ((new-handler) (request :get "/health"))]
    (is (= 200 (:status response)))
    (is (= {"status" "ok"} (response-body response)))))

(deftest serves-the-checked-in-openapi-contract
  (let [response ((new-handler) (request :get "/openapi.json"))]
    (is (= 200 (:status response)))
    (is (= (contract) (response-body response)))))

(deftest contract-declares-task-commands-and-post-queries
  (let [paths (get (contract) "paths")]
    (is (= #{"/health"
             "/openapi.json"
             "/commands/add-product-item"
             "/commands/remove-product-item"
             "/commands/confirm-cart"
             "/commands/cancel-cart"
             "/queries/get-cart"
             "/queries/get-cart-events"}
           (set (keys paths))))
    (doseq [path ["/commands/add-product-item"
                  "/commands/remove-product-item"
                  "/commands/confirm-cart"
                  "/commands/cancel-cart"
                  "/queries/get-cart"
                  "/queries/get-cart-events"]]
      (is (= #{"post"} (set (keys (get paths path))))))))

(deftest contract-command-and-query-routes-are-mounted
  (let [handler (new-handler)]
    (doseq [path ["/commands/add-product-item"
                  "/commands/remove-product-item"
                  "/commands/confirm-cart"
                  "/commands/cancel-cart"
                  "/queries/get-cart"
                  "/queries/get-cart-events"]]
      (testing path
        (let [status (:status (handler (request :post path {})))]
          (is (not= 404 status))
          (is (not= 405 status)))))
    (is (= 405 (:status (handler (request :get "/queries/get-cart")))))
    (is (= 405 (:status (handler (request :get "/queries/get-cart-events")))))))

(deftest openapi-contract-validates-representative-request-bodies
  (assert-request-contract "/commands/add-product-item" "post" add-item-task)
  (assert-request-contract "/commands/add-product-item"
                           "post"
                           (assoc add-item-task "expected-version" 0))
  (assert-request-contract "/commands/add-product-item"
                           "post"
                           (assoc add-item-task "expected-version" "any"))
  (assert-request-contract "/commands/remove-product-item" "post" add-item-task)
  (assert-request-contract "/commands/confirm-cart" "post" confirm-task)
  (assert-request-contract "/commands/cancel-cart" "post" confirm-task)
  (assert-request-contract "/queries/get-cart" "post" cart-query-request)
  (assert-request-contract "/queries/get-cart-events" "post" cart-query-request)
  (let [openapi (contract)
        schema  (request-schema openapi "/commands/add-product-item" "post")]
    (is (seq (schema-errors openapi
                            []
                            schema
                            (assoc add-item-task "expected-version" "0")))
        "numeric expected-version strings must not satisfy the OpenAPI contract")))

(deftest live-http-responses-conform-to-openapi-contract
  (let [handler (new-handler)]
    (assert-response-contract "/health" "get" (handler (request :get "/health")))
    (assert-response-contract "/openapi.json"
                              "get"
                              (handler (request :get "/openapi.json")))

    (let [add-created (handler (request :post
                                        "/commands/add-product-item"
                                        add-item-task))]
      (is (= 201 (:status add-created)))
      (assert-response-contract "/commands/add-product-item" "post" add-created))

    (let [add-existing (handler (request :post
                                         "/commands/add-product-item"
                                         (assoc add-item-task
                                                "expected-version"
                                                "any")))]
      (is (= 200 (:status add-existing)))
      (assert-response-contract "/commands/add-product-item" "post" add-existing))

    (let [summary (handler (request :post "/queries/get-cart" cart-query-request))]
      (is (= 200 (:status summary)))
      (assert-response-contract "/queries/get-cart" "post" summary))

    (let [events (handler (request :post
                                   "/queries/get-cart-events"
                                   cart-query-request))]
      (is (= 200 (:status events)))
      (assert-response-contract "/queries/get-cart-events" "post" events))

    (let [conflict (handler (request :post
                                     "/commands/add-product-item"
                                     (assoc add-item-task "expected-version" 0)))]
      (is (= 409 (:status conflict)))
      (assert-response-contract "/commands/add-product-item" "post" conflict))

    (let [bad-request (handler (request :post
                                        "/commands/add-product-item"
                                        (assoc add-item-task
                                               "expected-version"
                                               "banana")))]
      (is (= 400 (:status bad-request)))
      (assert-response-contract "/commands/add-product-item" "post" bad-request))

    (let [confirm-ok (handler (request :post "/commands/confirm-cart" confirm-task))]
      (is (= 200 (:status confirm-ok)))
      (assert-response-contract "/commands/confirm-cart" "post" confirm-ok)))

  (let [handler (new-handler)
        rejected (handler (request :post
                                   "/commands/confirm-cart"
                                   {"cart-id" "empty"}))]
    (is (= 422 (:status rejected)))
    (assert-response-contract "/commands/confirm-cart" "post" rejected))

  (let [handler (new-handler)
        _       (handler (request :post
                                  "/commands/add-product-item"
                                  (add-item-task-for "remove-c1")))
        removed (handler (request :post
                                  "/commands/remove-product-item"
                                  (add-item-task-for "remove-c1")))]
    (is (= 200 (:status removed)))
    (assert-response-contract "/commands/remove-product-item" "post" removed))

  (let [handler   (new-handler)
        _         (handler (request :post
                                    "/commands/add-product-item"
                                    (add-item-task-for "cancel-c1")))
        cancelled (handler (request :post
                                    "/commands/cancel-cart"
                                    {"cart-id" "cancel-c1"}))]
    (is (= 200 (:status cancelled)))
    (assert-response-contract "/commands/cancel-cart" "post" cancelled))

  (let [handler   (new-handler)
        bad-query (handler (request :post "/queries/get-cart" {}))]
    (is (= 400 (:status bad-query)))
    (assert-response-contract "/queries/get-cart" "post" bad-query)))

(deftest commands-go-through-the-command-port
  (let [called       (atom nil)
        cart-command (reify cart-command/CartCommand
                       (handle-cart-command [_ cart-id command]
                         (reset! called {:arity :derived
                                         :cart-id cart-id
                                         :command command})
                         [:ok {:cart-id cart-id
                               :stream-id "from-command-use-case"
                               :events []
                               :version 1
                               :created-new-stream? true}])
                       (handle-cart-command [_ cart-id command expected-version]
                         (reset! called {:arity :expected
                                         :cart-id cart-id
                                         :command command
                                         :expected-version expected-version})
                         [:ok {:cart-id cart-id
                               :stream-id "from-command-use-case"
                               :events []
                               :version 1
                               :created-new-stream? true}]))
        cart-query   (reify cart-query/CartQuery
                       (cart-summary [_ _] {})
                       (cart-events [_ _] {}))
        handler      (http/handler {:cart-command cart-command
                                    :cart-query   cart-query
                                    :clock        (constantly now)})
        response     (handler (request :post
                                       "/commands/add-product-item"
                                       add-item-task))
        body         (response-body response)]
    (is (= 201 (:status response)))
    (is (= "from-command-use-case" (get body "stream-id")))
    (is (= :derived (:arity @called)))
    (is (= "c1" (:cart-id @called)))
    (is (= :cart.command/add-product-item
           (get-in @called [:command :type])))
    (is (= {:now now}
           (get-in @called [:command :metadata])))
    (let [response (handler (request :post
                                     "/commands/add-product-item"
                                     (assoc add-item-task "expected-version" 0)))]
      (is (= 201 (:status response)))
      (is (= :expected (:arity @called)))
      (is (= 0 (:expected-version @called))))))

(deftest command-post-appends-and-get-reads-cart
  (let [handler (new-handler)]
    (testing "task command endpoint appends through the cart command use case"
      (let [response (handler (request :post
                                       "/commands/add-product-item"
                                       add-item-task))
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

    (testing "POST /queries/get-cart reads through the query handler"
      (let [response (handler (request :post "/queries/get-cart" cart-query-request))
            body     (response-body response)]
        (is (= 200 (:status response)))
        (is (true? (get body "exists?")))
        (is (= 1 (get body "version")))
        (is (= "opened" (get-in body ["state" "status"])))
        (is (= 2 (get-in body ["state" "product-items" "sku-1"])))))

    (testing "POST /queries/get-cart-events exposes the stream read contract"
      (let [response (handler (request :post
                                       "/queries/get-cart-events"
                                       cart-query-request))
            body     (response-body response)]
        (is (= 200 (:status response)))
        (is (= 1 (get body "version")))
        (is (= ["cart.event/product-item-added"]
               (mapv #(get % "type") (get body "events"))))))))

(deftest english-chinese-and-arabic-text-round-trips-through-http-json
  (let [handler (new-handler)
        created (handler (request :post
                                  "/commands/add-product-item"
                                  unicode-add-item-task))
        queried (handler (request :post
                                  "/queries/get-cart"
                                  unicode-cart-query-request))
        events  (handler (request :post
                                  "/queries/get-cart-events"
                                  unicode-cart-query-request))]
    (is (= "application/json; charset=utf-8"
           (get-in created [:headers "content-type"])))
    (is (= 201 (:status created)))
    (is (= 200 (:status queried)))
    (is (= 200 (:status events)))

    (is (= unicode-cart-id (get (response-body created) "cart-id")))
    (is (= 2
           (get-in (response-body queried)
                   ["state" "product-items" unicode-product-id])))
    (is (= unicode-product-id
           (get-in (response-body events)
                   ["events" 0 "data" "product-item" "product-id"])))))

(deftest business-errors-return-422-and-do-not-write
  (let [handler  (new-handler)
        response (handler (request :post "/commands/confirm-cart" confirm-task))
        body     (response-body response)]
    (is (= 422 (:status response)))
    (is (= "command-rejected" (get body "error")))
    (is (= "not-opened" (get body "reason")))
    (is (= 0 (get (response-body
                   (handler (request :post
                                     "/queries/get-cart-events"
                                     cart-query-request)))
                  "version")))))

(deftest stale-expected-version-returns-conflict
  (let [handler (new-handler)]
    (handler (request :post "/commands/add-product-item" add-item-task))
    (let [response (handler (request :post
                                     "/commands/add-product-item"
                                     (assoc add-item-task "expected-version" 0)))
          body     (response-body response)]
      (is (= 409 (:status response)))
      (is (= "version-conflict" (get body "error")))
      (is (= 0 (get body "expected")))
      (is (= 1 (get body "current"))))))

(deftest explicit-any-expected-version-appends-without-conflict
  (let [handler (new-handler)]
    (handler (request :post "/commands/add-product-item" add-item-task))
    (let [response (handler (request :post
                                     "/commands/add-product-item"
                                     (assoc add-item-task
                                            "expected-version"
                                            "any")))
          body     (response-body response)]
      (is (= 200 (:status response)))
      (is (= 2 (get body "version"))))))

(deftest rejects-invalid-http-input
  (let [handler (new-handler)]
    (testing "malformed JSON"
      (let [response (handler {:request-method :post
                               :uri            "/commands/add-product-item"
                               :headers        {"content-type" "application/json"}
                               :body           (ByteArrayInputStream.
                                                (.getBytes "{\"type\""
                                                           StandardCharsets/UTF_8))})]
        (is (= 400 (:status response)))
        (is (= "invalid-json" (get (response-body response) "error")))))

    (testing "bad expected version"
      (let [response (handler (request :post
                                       "/commands/add-product-item"
                                       (assoc add-item-task
                                              "expected-version"
                                              "banana")))]
        (is (= 400 (:status response)))
        (is (= "invalid-expected-version" (get (response-body response) "error")))))

    (testing "numeric expected version must be a JSON number, not a string"
      (let [response (handler (request :post
                                       "/commands/add-product-item"
                                       (assoc add-item-task
                                              "expected-version"
                                              "0")))]
        (is (= 400 (:status response)))
        (is (= "invalid-expected-version" (get (response-body response) "error")))))

    (testing "task body fails schema validation before reaching app code"
      (let [response (handler (request :post
                                       "/commands/add-product-item"
                                       {"cart-id" "c1"}))]
        (is (= 400 (:status response)))
        (is (= "invalid-command" (get (response-body response) "error")))))

    (testing "out-of-contract command fields are rejected"
      (let [response (handler (request :post
                                       "/commands/confirm-cart"
                                       (assoc confirm-task "unexpected" true)))]
        (is (= 400 (:status response)))
        (is (= "invalid-command" (get (response-body response) "error")))))

    (testing "query body must contain a cart id"
      (let [response (handler (request :post "/queries/get-cart" {}))]
        (is (= 400 (:status response)))
        (is (= "invalid-query" (get (response-body response) "error")))))))

(deftest old-resource-shaped-routes-are-not-part-of-the-contract
  (let [handler (new-handler)]
    (is (= 404 (:status (handler (request :get "/carts/c1")))))
    (is (= 404 (:status (handler (request :get "/carts/c1/events")))))
    (is (= 404 (:status (handler (request :post
                                          "/carts/c1/commands"
                                          add-item-task)))))))
