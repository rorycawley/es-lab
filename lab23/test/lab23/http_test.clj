(ns lab23.http-test
  "Focused tests of the primary HTTP adapter as a map-to-map function.

  Not one test in this namespace needs a socket, a port or an HTTP client —
  except the last, a deliberately small System/E2E smoke test using real
  Jetty and real Postgres. The map-to-map component tests use driven fakes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab23.adapter.http :as http]
            [lab23.fixture :as fixture]
            [lab23.port.driven :as driven]
            [lab23.schema.command :as command]
            [lab23.system :as system])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(defn- with-handler [f]
  (let [sys (system/start (system/in-memory))]
    (try (f (http/handler (system/app sys))) (finally (system/stop sys)))))

(defn- post [handler uri body]
  (let [response (handler {:request-method :post :uri uri
                           :body (java.io.StringReader. (json/write-str body))})]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- post-raw [handler uri body]
  (let [response (handler {:request-method :post :uri uri
                           :body (java.io.StringReader. body)})]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- GET [handler uri]
  (let [response (handler {:request-method :get :uri uri})]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- post-over-http [url body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "content-type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))))

;; ---------------------------------------------------------------------------
;; The happy path
;; ---------------------------------------------------------------------------

(deftest an-act-is-posted-to-and-returns-the-facts-test
  (with-handler
    (fn [h]
      (let [response (post h "/v1/restocks" {:flavour "vanilla" :quantity 2})]
        (is (= 200 (:status response)))
        (testing "the response is what happened, not the resource"
          (is (= [{:type "truck-loaded" :version 1
                   :data {:flavour "vanilla" :quantity 2}}]
                 (:recorded (:parsed response))))
          (is (nil? (:stock (:parsed response)))
              "current state is a query's business, not a command's"))))))

(deftest a-query-is-a-get-test
  (with-handler
    (fn [h]
      (post h "/v1/restocks" {:flavour "vanilla" :quantity 3})
      (let [body (:body (GET h "/v1/stock"))]
        (is (= {"vanilla" 3} (get (json/read-str body) "stock"))
            "read with no key-fn at all: every key is a string, field names too")
        (testing "and a blanket :key-fn keyword would turn that data into symbols"
          ;; `:key-fn keyword` is safe exactly where keys are *field names*,
          ;; which are known in advance. Here they are flavours, which are not
          ;; — so this is the same rule as the one about values, one level up.
          ;; A map keyed by domain data is a thing to think twice about
          ;; putting on a wire.
          (is (= {:vanilla 3}
                 (:stock (json/read-str body :key-fn keyword)))))))))

(deftest ensuring-an-already-satisfied-stock-level-is-an-accepted-no-op-test
  (with-handler
    (fn [h]
      (post h "/v1/restocks" {:flavour "vanilla" :quantity 3})
      (let [response (post h "/v1/replenishments"
                           {:flavour "vanilla" :quantity 2})]
        (is (= 200 (:status response)))
        (is (= [] (:recorded (:parsed response))))
        (is (= {:vanilla 3} (:stock (:parsed (GET h "/v1/stock")))))))))

;; ---------------------------------------------------------------------------
;; Status codes are lab 2's two columns
;; ---------------------------------------------------------------------------

(deftest malformed-is-400-and-refused-is-422-test
  (with-handler
    (fn [h]
      (testing "400 — the schema refused it; the domain never saw it"
        (let [response (post h "/v1/sales" {:flavour "tarmac"})]
          (is (= 400 (:status response)))
          (is (= "malformed" (:error (:parsed response))))
          (is (some? (:detail (:parsed response))) "and it says what was wrong")))

      (testing "422 — well-formed, and the domain said no"
        (let [response (post h "/v1/sales" {:flavour "vanilla"})]
          (is (= 422 (:status response)))
          (is (= "refused" (:error (:parsed response))))
          (is (= "Sold out" (:detail (:parsed response))))))

      (testing "the difference is whether retrying unchanged could ever work"
        (post h "/v1/restocks" {:flavour "vanilla" :quantity 1})
        (is (= 200 (:status (post h "/v1/sales" {:flavour "vanilla"})))
            "the 422 became a 200 because state changed")
        (is (= 400 (:status (post h "/v1/sales" {:flavour "tarmac"})))
            "the 400 is still a 400, and always will be")))))

(deftest malformed-json-is-400-test
  (with-handler
    (fn [h]
      (let [response (post-raw h "/v1/restocks" "{not-json")]
        (is (= 400 (:status response)))
        (is (= "malformed" (:error (:parsed response))))))))

(defn- faulting-deps [failure]
  (let [store (reify driven/EventStore
                (command-result [_ _ _] nil)
                (commit-command [_ _ _ _ _ _] (throw failure))
                (read-stream [_ _] [])
                (stream-version [_ _] 0)
                (read-since [_ _] []))]
    {:store store
     :clock (reify driven/Clock (now [_] #inst "2026-09-01T09:00:00.000-00:00"))
     :ids (reify driven/Ids (new-id [_] (random-uuid)))}))

(deftest concurrency-is-409-but-other-failures-remain-server-failures-test
  (let [conflict (ex-info "moved" {:reason :concurrent-modification})
        conflict-response (post (http/handler (faulting-deps conflict))
                                "/v1/restocks"
                                {:flavour "vanilla" :quantity 1})]
    (is (= 409 (:status conflict-response)))
    (is (= "conflict" (:error (:parsed conflict-response)))))
  (testing "identity and infrastructure failures are not client mistakes"
    (let [collision (ex-info "collision" {:reason :command-id-collision})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"collision"
           (post (http/handler (faulting-deps collision))
                 "/v1/restocks"
                 {:flavour "vanilla" :quantity 1}))))))

(deftest an-unknown-endpoint-is-404-test
  (with-handler
    (fn [h]
      (is (= 404 (:status (post h "/v1/nonsense" {})))))))

;; ---------------------------------------------------------------------------
;; The external message is validated before internal identity exists
;; ---------------------------------------------------------------------------

(deftest the-http-body-is-the-closed-external-message-data-test
  (is (nil? (command/validate-message
             {:type :buy-flavour :data {:flavour "vanilla"}})))
  (is (some? (command/validate-message
              {:type :buy-flavour
               :data {:flavour "vanilla" :command/id (random-uuid)}}))
      "an HTTP client cannot inject internal identity into command data"))

;; ---------------------------------------------------------------------------
;; The API surface and the command vocabulary are one list
;; ---------------------------------------------------------------------------

(defn- route-table []
  (let [walk (fn walk [node prefix]
               (let [[path & more] node
                     full (str prefix path)]
                 (if (map? (first more))
                   [[full (first more)]]
                   (mapcat #(walk % full) more))))]
    (mapcat #(walk % "") (http/routes {}))))

(deftest every-command-endpoint-names-a-real-command-test
  (doseq [[path methods] (route-table)
          [method data] methods
          :let [command (:command data)]
          :when command]
    (is (contains? command/by-type command)
        (str method " " path " maps to " command ", which is not a command type"))))

(deftest every-command-has-an-endpoint-test
  (testing "adding a command without exposing it fails the build too"
    (let [exposed (set (keep (fn [[_ methods]] (some :command (vals methods)))
                             (route-table)))]
      (doseq [command (keys command/by-type)]
        (is (contains? exposed command)
            (str command " has no endpoint — the API surface and the command "
                 "vocabulary must be the same list"))))))

(deftest commands-are-posts-and-nothing-else-test
  (doseq [[path methods] (route-table)
          [method data] methods
          :when (:command data)]
    (is (= :post method) (str path " exposes a command over " method))))

(deftest the-only-get-outside-v1-is-operational-test
  (doseq [[path methods] (route-table)
          [method _] methods
          :when (= :get method)]
    (is (or (= "/health" path) (str/starts-with? path "/v1/"))
        (str "unexpected GET at " path))))

;; ---------------------------------------------------------------------------
;; One test, one socket
;; ---------------------------------------------------------------------------

(deftest the-system-serves-a-use-case-through-real-infrastructure-test
  (if (System/getenv "ESLAB_SKIP_DOCKER")
    (is true "real-infrastructure smoke test explicitly skipped")
    (testing "one smoke test crosses HTTP, the application and real Postgres"
      (let [sys (system/start (system/serving (fixture/postgres-system {}) 0))]
        (try
          (let [port (.getLocalPort (aget (.getConnectors (:server (:http sys))) 0))
                response (post-over-http (str "http://localhost:" port "/v1/restocks")
                                         {:flavour "vanilla" :quantity 2})]
            (is (= 200 (.statusCode response)))
            (is (= {"vanilla" 2}
                   (get (json/read-str
                         (slurp (str "http://localhost:" port "/v1/stock")))
                        "stock"))))
          (finally (system/stop sys)))))))
