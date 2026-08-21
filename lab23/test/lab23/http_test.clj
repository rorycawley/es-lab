(ns lab23.http-test
  "The web layer, tested as what it is: a function from a map to a map.

  Not one test in this namespace needs a socket, a port or an HTTP client —
  except the last, which starts Jetty once to prove the wiring."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab23.adapter.http :as http]
            [lab23.schema.command :as command]
            [lab23.system :as system]))

(defn- with-handler [f]
  (let [sys (system/start (system/in-memory))]
    (try (f (http/handler (system/app sys))) (finally (system/stop sys)))))

(defn- post [handler uri body]
  (let [response (handler {:request-method :post :uri uri
                           :body (java.io.StringReader. (json/write-str body))})]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- GET [handler uri]
  (let [response (handler {:request-method :get :uri uri})]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

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

(deftest an-unknown-endpoint-is-404-test
  (with-handler
    (fn [h]
      (is (= 404 (:status (post h "/v1/nonsense" {})))))))

;; ---------------------------------------------------------------------------
;; The wire loses the same things the store loses
;; ---------------------------------------------------------------------------

(deftest the-wire-form-is-already-the-domain-form-test
  (testing "the body a client sends needs no translating"
    ;; This test used to assert the opposite: that a JSON body arrived as
    ;; strings, the schema wanted keywords, and a decode step stood between
    ;; them. The decode step is still here — see below — but it has nothing
    ;; to do for these commands, because the domain no longer writes anything
    ;; JSON cannot carry.
    (let [wire {:command/id    (random-uuid)
                :command/type  :buy-flavour
                :data          {:flavour "vanilla"}}]
      (is (nil? (command/validate wire))
          "valid exactly as it arrived off the wire")
      (is (= wire (command/decode wire))
          "and decoding it is the identity")))

  (testing "the decode step stays at the boundary anyway"
    ;; It costs one line and it is where a coercion belongs the day a command
    ;; grows a field JSON does damage — a uuid, an instant, a decimal. Lab 22
    ;; still needs it on the way *out*, for :truck-id.
    (is (some? (command/decode {:command/type :buy-flavour :data {:flavour "vanilla"}})))))

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

(deftest the-wiring-actually-serves-test
  (testing "everything above ran without a server; this proves there is one"
    (let [sys (system/start (system/serving (system/in-memory) 0))]
      (try
        (let [port (.getLocalPort (aget (.getConnectors (:server (:http sys))) 0))
              body (slurp (str "http://localhost:" port "/health"))]
          (is (= {:status "ok"} (json/read-str body :key-fn keyword))))
        (finally (system/stop sys))))))
