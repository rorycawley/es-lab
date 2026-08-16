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
  (:import [com.atlassian.oai.validator OpenApiInteractionValidator]
           [com.atlassian.oai.validator.model Request$Method
            SimpleRequest$Builder
            SimpleResponse$Builder]
           [com.atlassian.oai.validator.report SimpleValidationReportFormat]
           [io.swagger.v3.parser OpenAPIV3Parser]
           [io.swagger.v3.parser.core.models ParseOptions]
           [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def now 1735689600000)

(defn- new-handler []
  (let [event-store (memory/make-store)]
    (http/handler {:cart-command (app-command/make-event-store-command
                                  event-store
                                  {:min-timeout 1})
                   :cart-query   (app-query/make-event-store-query event-store)
                   :clock        (constantly now)})))

(defn- failing-handler
  "Every port call blows up, so the adapter's last-resort 500 is reachable from
   a live handler rather than asserted from a hand-written body."
  []
  (let [boom (fn [] (throw (RuntimeException. "event store unavailable")))]
    (http/handler {:cart-command (reify cart-command/CartCommand
                                   (handle-cart-command [_ _ _] (boom))
                                   (handle-cart-command [_ _ _ _] (boom)))
                   :cart-query   (reify cart-query/CartQuery
                                   (cart-summary [_ _] (boom))
                                   (cart-events [_ _] (boom)))
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

(defn- contract-resource []
  (io/resource "openapi/cart-api.openapi.json"))

(defn- contract-text []
  (slurp (contract-resource)))

(defn- contract []
  (json/parse-string (contract-text)))

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

(defn- parse-options []
  (doto (ParseOptions.)
    (.setResolve true)
    (.setResolveCombinators true)
    (.setValidateInternalRefs true)
    (.setValidateExternalRefs false)))

(defn- parse-openapi-contract []
  (.readContents (OpenAPIV3Parser.) (contract-text) nil (parse-options)))

(def ^:private openapi-validator
  (delay (-> (OpenApiInteractionValidator/createForInlineApiSpecification
              (contract-text))
             (.withStrictOperationPathMatching)
             (.build))))

(defn- request-method [method]
  (Request$Method/valueOf (str/upper-case method)))

(defn- validator-request-builder [method path]
  (case method
    "get"     (SimpleRequest$Builder/get path)
    "post"    (SimpleRequest$Builder/post path)
    "put"     (SimpleRequest$Builder/put path)
    "patch"   (SimpleRequest$Builder/patch path)
    "delete"  (SimpleRequest$Builder/delete path)
    "head"    (SimpleRequest$Builder/head path)
    "options" (SimpleRequest$Builder/options path)
    "trace"   (SimpleRequest$Builder/trace path)))

(defn- validator-request [path method body]
  (let [builder (validator-request-builder method path)]
    (when (some? body)
      (.withContentType builder "application/json")
      (.withBody builder (json/generate-string body)))
    (.build builder)))

(defn- validator-response [response]
  (let [builder (SimpleResponse$Builder/status (:status response))]
    (when-let [content-type (get-in response [:headers "content-type"])]
      (.withContentType builder content-type))
    (when-let [body (:body response)]
      (.withBody builder body))
    (.build builder)))

(defn- report-text [report]
  (.apply (SimpleValidationReportFormat/getInstance) report))

(defn- assert-valid-report [label report]
  (is (not (.hasErrors report))
      (str label "\n" (report-text report))))

(defn- request-validation-report [path method body]
  (.validateRequest @openapi-validator
                    (validator-request path method body)))

(defn- response-validation-report [path method response]
  (.validateResponse @openapi-validator
                     path
                     (request-method method)
                     (validator-response response)))

(defn- assert-request-contract [path method body]
  (assert-valid-report (str method " " path " request")
                       (request-validation-report path method body)))

(defn- assert-response-contract [path method response]
  (assert-valid-report (str method " " path " "
                            (:status response)
                            " response")
                       (response-validation-report path method response)))

(def ^:private http-methods
  #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})

(defn- operations [openapi]
  (for [[path path-item] (sort-by key (get openapi "paths"))
        [method operation] (sort-by key path-item)
        :when (http-methods method)]
    {:path path :method method :operation operation}))

(defn- request-body-combinations []
  (let [openapi (contract)]
    (set
     (for [{:keys [path method operation]} (operations openapi)
           :let [request-body (some->> (get operation "requestBody")
                                       (resolve-ref-node openapi))]
           :when request-body
           content-type (sort (keys (get request-body "content")))]
       [path method content-type]))))

(defn- response-content-combinations []
  (let [openapi (contract)]
    (set
     (for [{:keys [path method operation]} (operations openapi)
           [status response] (sort-by key (get operation "responses"))
           :let [response (resolve-ref-node openapi response)]
           content-type (sort (keys (get response "content")))]
       [path method status content-type]))))

(defn- compatible-content-type? [declared actual]
  (or (= declared actual)
      (str/starts-with? actual (str declared ";"))))

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

(defn- cart-only-task-for [cart-id]
  {"cart-id" cart-id})

(defn- call
  ([handler method path]
   (handler (request method path)))
  ([handler method path body]
   (handler (request method path body))))

(defn- add-product-item! [handler cart-id]
  (call handler :post "/commands/add-product-item" (add-item-task-for cart-id)))

(defn- remove-product-item! [handler body]
  (call handler :post "/commands/remove-product-item" body))

(defn- confirm-cart! [handler body]
  (call handler :post "/commands/confirm-cart" body))

(defn- cancel-cart! [handler body]
  (call handler :post "/commands/cancel-cart" body))

(def ^:private valid-request-examples
  {["/commands/add-product-item" "post" "application/json"] add-item-task
   ["/commands/remove-product-item" "post" "application/json"] add-item-task
   ["/commands/confirm-cart" "post" "application/json"] confirm-task
   ["/commands/cancel-cart" "post" "application/json"] confirm-task
   ["/queries/get-cart" "post" "application/json"] cart-query-request
   ["/queries/get-cart-events" "post" "application/json"] cart-query-request})

(def ^:private live-response-examples
  {["/health" "get" "200" "application/json"]
   (fn [] (call (new-handler) :get "/health"))

   ["/openapi.json" "get" "200" "application/json"]
   (fn [] (call (new-handler) :get "/openapi.json"))

   ["/commands/add-product-item" "post" "201" "application/json"]
   (fn []
     (let [handler (new-handler)]
       (add-product-item! handler "add-created")))

   ["/commands/add-product-item" "post" "200" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "add-existing"]
       (add-product-item! handler cart-id)
       (call handler
             :post
             "/commands/add-product-item"
             (assoc (add-item-task-for cart-id) "expected-version" "any"))))

   ["/commands/add-product-item" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/commands/add-product-item" {}))

   ["/commands/add-product-item" "post" "409" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "add-conflict"]
       (add-product-item! handler cart-id)
       (call handler
             :post
             "/commands/add-product-item"
             (assoc (add-item-task-for cart-id) "expected-version" 0))))

   ["/commands/add-product-item" "post" "422" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "add-rejected"]
       (add-product-item! handler cart-id)
       (confirm-cart! handler (cart-only-task-for cart-id))
       (call handler
             :post
             "/commands/add-product-item"
             (assoc (add-item-task-for cart-id) "expected-version" "any"))))

   ["/commands/remove-product-item" "post" "200" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "remove-ok"]
       (add-product-item! handler cart-id)
       (remove-product-item! handler (add-item-task-for cart-id))))

   ["/commands/remove-product-item" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/commands/remove-product-item" {}))

   ["/commands/remove-product-item" "post" "409" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "remove-conflict"]
       (add-product-item! handler cart-id)
       (remove-product-item! handler
                             (assoc (add-item-task-for cart-id)
                                    "expected-version"
                                    0))))

   ["/commands/remove-product-item" "post" "422" "application/json"]
   (fn []
     (let [handler (new-handler)]
       (remove-product-item! handler
                             (add-item-task-for "remove-rejected"))))

   ["/commands/confirm-cart" "post" "200" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "confirm-ok"]
       (add-product-item! handler cart-id)
       (confirm-cart! handler (cart-only-task-for cart-id))))

   ["/commands/confirm-cart" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/commands/confirm-cart" {}))

   ["/commands/confirm-cart" "post" "409" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "confirm-conflict"]
       (add-product-item! handler cart-id)
       (confirm-cart! handler
                      (assoc (cart-only-task-for cart-id)
                             "expected-version"
                             0))))

   ["/commands/confirm-cart" "post" "422" "application/json"]
   (fn [] (confirm-cart! (new-handler) (cart-only-task-for "confirm-rejected")))

   ["/commands/cancel-cart" "post" "201" "application/json"]
   (fn []
     ;; Cancelling a cart that does not exist is a legal decision, so this
     ;; command creates its stream exactly like add-product-item does.
     (cancel-cart! (new-handler) (cart-only-task-for "cancel-created")))

   ["/commands/cancel-cart" "post" "200" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "cancel-ok"]
       (add-product-item! handler cart-id)
       (cancel-cart! handler (cart-only-task-for cart-id))))

   ["/commands/cancel-cart" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/commands/cancel-cart" {}))

   ["/commands/cancel-cart" "post" "409" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "cancel-conflict"]
       (add-product-item! handler cart-id)
       (cancel-cart! handler
                     (assoc (cart-only-task-for cart-id)
                            "expected-version"
                            0))))

   ["/commands/cancel-cart" "post" "422" "application/json"]
   (fn []
     (let [handler (new-handler)
           cart-id "cancel-rejected"]
       (add-product-item! handler cart-id)
       (cancel-cart! handler (cart-only-task-for cart-id))
       (cancel-cart! handler
                     (assoc (cart-only-task-for cart-id)
                            "expected-version"
                            "any"))))

   ["/queries/get-cart" "post" "200" "application/json"]
   (fn [] (call (new-handler) :post "/queries/get-cart" cart-query-request))

   ["/queries/get-cart" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/queries/get-cart" {}))

   ["/queries/get-cart-events" "post" "200" "application/json"]
   (fn [] (call (new-handler)
                :post
                "/queries/get-cart-events"
                cart-query-request))

   ["/queries/get-cart-events" "post" "400" "application/json"]
   (fn [] (call (new-handler) :post "/queries/get-cart-events" {}))

   ["/commands/add-product-item" "post" "500" "application/json"]
   (fn [] (add-product-item! (failing-handler) "boom"))

   ["/commands/remove-product-item" "post" "500" "application/json"]
   (fn [] (remove-product-item! (failing-handler) (add-item-task-for "boom")))

   ["/commands/confirm-cart" "post" "500" "application/json"]
   (fn [] (confirm-cart! (failing-handler) (cart-only-task-for "boom")))

   ["/commands/cancel-cart" "post" "500" "application/json"]
   (fn [] (cancel-cart! (failing-handler) (cart-only-task-for "boom")))

   ["/queries/get-cart" "post" "500" "application/json"]
   (fn [] (call (failing-handler) :post "/queries/get-cart" cart-query-request))

   ["/queries/get-cart-events" "post" "500" "application/json"]
   (fn [] (call (failing-handler)
                :post
                "/queries/get-cart-events"
                cart-query-request))})

(deftest health-check
  (let [response ((new-handler) (request :get "/health"))]
    (is (= 200 (:status response)))
    (is (= {"status" "ok"} (response-body response)))))

(deftest serves-the-checked-in-openapi-contract
  (let [response ((new-handler) (request :get "/openapi.json"))]
    (is (= 200 (:status response)))
    (is (= (contract) (response-body response)))))

(deftest checked-in-openapi-contract-parses-with-swagger-parser
  (let [result (parse-openapi-contract)]
    (is (some? (.getOpenAPI result)))
    (is (= [] (vec (.getMessages result)))
        (str "OpenAPI parser messages: " (pr-str (vec (.getMessages result)))))))

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

(deftest contract-operations-are-mounted-with-declared-methods
  (let [handler (new-handler)]
    (doseq [{:keys [path method]} (operations (contract))]
      (testing (str method " " path)
        (let [status (:status (call handler (keyword method) path {}))]
          (is (not= 404 status))
          (is (not= 405 status)))))
    (is (= 405 (:status (handler (request :get "/queries/get-cart")))))
    (is (= 405 (:status (handler (request :get "/queries/get-cart-events")))))))

(deftest every-openapi-request-body-content-type-has-a-valid-example
  (let [expected (request-body-combinations)
        actual   (set (keys valid-request-examples))]
    (is (= expected actual)
        (str "request example keys must match OpenAPI request bodies. missing="
             (pr-str (sort (remove actual expected)))
             " extra="
             (pr-str (sort (remove expected actual)))))
    (doseq [[path method content-type :as key] (sort expected)]
      (testing (str method " " path " " content-type)
        (is (= "application/json" content-type))
        (assert-request-contract path method (get valid-request-examples key)))))

  (doseq [expected-version [0 "any" "stream-does-not-exist"]]
    (assert-request-contract "/commands/add-product-item"
                             "post"
                             (assoc add-item-task
                                    "expected-version"
                                    expected-version)))
  (let [report (request-validation-report
                "/commands/add-product-item"
                "post"
                (assoc add-item-task "expected-version" "0"))]
    (is (.hasErrors report)
        "numeric expected-version strings must not satisfy the OpenAPI contract")))

(deftest every-openapi-json-response-has-a-live-conforming-example
  (let [expected (response-content-combinations)
        actual   (set (keys live-response-examples))]
    (is (= expected actual)
        (str "live response example keys must match OpenAPI responses. missing="
             (pr-str (sort (remove actual expected)))
             " extra="
             (pr-str (sort (remove expected actual)))))
    (doseq [[path method status content-type :as key] (sort expected)
            :let [example (get live-response-examples key)]
            :when example]
      (testing (str method " " path " " status " " content-type)
        (let [response (example)
              actual-content-type (get-in response [:headers "content-type"])]
          (is (= (Long/parseLong status) (:status response)))
          (is (compatible-content-type? content-type actual-content-type)
              (str "declared " content-type ", got " (pr-str actual-content-type)))
          (assert-response-contract path method response))))))

(def ^:private command-endpoints
  {"/commands/add-product-item"    add-item-task-for
   "/commands/remove-product-item" add-item-task-for
   "/commands/confirm-cart"        cart-only-task-for
   "/commands/cancel-cart"         cart-only-task-for})

(defn- declared-statuses [path method]
  (set (keys (get-in (contract) ["paths" path method "responses"]))))

(defn- command-situations
  "Drives one command endpoint through the situations a client can actually put
   a cart in. Handlers are stateful, so the cases run in order and each sees
   whatever the previous one left behind."
  [path body-for]
  (let [cart-id (str "sweep-" (random-uuid))
        missing (new-handler)
        opened  (new-handler)
        closed  (new-handler)]
    (add-product-item! opened cart-id)
    (add-product-item! closed cart-id)
    (confirm-cart! closed (cart-only-task-for cart-id))
    [["the cart does not exist" (call missing :post path (body-for cart-id))]
     ["the cart is open"        (call opened :post path (body-for cart-id))]
     ["the cart is closed"      (call closed :post path (body-for cart-id))]
     ["the expected version is stale"
      (call opened :post path (assoc (body-for cart-id) "expected-version" 0))]
     ["the expected version is any"
      (call opened :post path (assoc (body-for cart-id) "expected-version" "any"))]
     ["the body is not a valid task" (call missing :post path {})]]))

(deftest every-status-the-handler-emits-is-declared-in-the-contract
  (testing "SPEC R7.8 — walks handler -> contract. The example table above walks
            contract -> handler, so it is blind to a status the adapter really
            returns but the document never declared."
    (doseq [[path body-for] (sort-by key command-endpoints)
            :let [declared (declared-statuses path "post")]
            [situation response] (command-situations path body-for)]
      (testing (str "post " path " when " situation)
        (is (contains? declared (str (:status response)))
            (str "the handler returned " (:status response)
                 " but the OpenAPI document only declares "
                 (pr-str (sort declared))))
        (assert-response-contract path "post" response)))))

(deftest cancelling-a-cart-that-does-not-exist-creates-its-stream
  (testing "a legal decision against an :empty cart creates the stream, so this
            command is a 201 exactly like add-product-item"
    (let [response (cancel-cart! (new-handler)
                                 (cart-only-task-for (str "cancel-" (random-uuid))))
          body     (response-body response)]
      (is (= 201 (:status response)))
      (is (true? (get body "created-new-stream?")))
      (is (= 1 (get body "version")))
      (is (= ["cart.event/cancelled"]
             (mapv #(get % "type") (get body "events")))))))

(deftest port-failures-become-a-declared-500
  (testing "an unhandled failure below the adapter must not leak a stack trace
            or an undeclared status"
    (doseq [[path body] [["/commands/add-product-item" (add-item-task-for "boom")]
                         ["/commands/remove-product-item" (add-item-task-for "boom")]
                         ["/commands/confirm-cart" (cart-only-task-for "boom")]
                         ["/commands/cancel-cart" (cart-only-task-for "boom")]
                         ["/queries/get-cart" cart-query-request]
                         ["/queries/get-cart-events" cart-query-request]]]
      (testing path
        (let [response (call (failing-handler) :post path body)]
          (is (= 500 (:status response)))
          (is (= "internal-server-error" (get (response-body response) "error")))
          (is (contains? (declared-statuses path "post") "500"))
          (assert-response-contract path "post" response))))))

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
