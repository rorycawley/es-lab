(ns lab24.http-test
  "Focused tests of the primary HTTP adapter as a map-to-map function.

  Not one test in this namespace needs a socket for *our* server — except the
  last, a deliberately small System/E2E smoke test with real Postgres. The
  map-to-map component tests use driven fakes. The identity provider is
  a different matter: it is a real server on a real port, because a token you
  minted yourself proves nothing about a token you were given."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab24.adapter.auth :as auth]
            [lab24.adapter.http :as http]
            [lab24.fixture :as fixture]
            [lab24.mock-idp :as mock-idp]
            [lab24.port.driven :as driven]
            [lab24.schema.command :as command]
            [lab24.system :as system])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(defn- with-handler
  "A provider, a system, a handler, and a way to log in as anybody."
  [f]
  (let [idp (mock-idp/start!)]
    (try
      (let [sys (system/start (system/in-memory {:oidc (mock-idp/oidc-config idp)}))]
        (try (f {:handler (http/handler (system/app sys))
                 :app     (system/app sys)
                 :login   (fn [persona] (mock-idp/login idp persona))})
             (finally (system/stop sys))))
      (finally (mock-idp/stop! idp)))))

(def truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- request [method uri tokens body]
  (cond-> {:request-method method :uri uri}
    tokens (assoc :headers {"authorization" (mock-idp/bearer tokens)})
    body   (assoc :body (java.io.StringReader. (json/write-str body)))))

(defn- call [handler method uri tokens body]
  (let [response (handler (request method uri tokens body))]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- post [handler uri tokens body] (call handler :post uri tokens body))
(defn- GET  [handler uri tokens]      (call handler :get uri tokens nil))

(defn- post-over-http [url tokens body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "content-type" "application/json")
                    (.header "authorization" (mock-idp/bearer tokens))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))))

(defn- ready
  "Roster Dana and put vanilla on the truck, as the depot would."
  [handler login]
  (let [depot (login :rudi)]
    (post handler "/v1/driver-assignments" depot {:driver-id "USR-83721"})
    (post handler "/v1/restocks" depot {:flavour "vanilla" :quantity 2})
    depot))

;; ---------------------------------------------------------------------------
;; The happy path
;; ---------------------------------------------------------------------------

(deftest an-act-is-posted-to-and-returns-the-facts-test
  (with-handler
    (fn [{:keys [handler login]}]
      (let [response (post handler "/v1/restocks" (login :rudi)
                           {:flavour "vanilla" :quantity 2})]
        (is (= 200 (:status response)))
        (testing "the response is what happened, not the resource"
          (is (= [{:type "truck-loaded" :version 1
                   :data {:flavour "vanilla" :quantity 2}}]
                 (:recorded (:parsed response))))
          (is (nil? (:stock (:parsed response)))
              "current state is a query's business, not a command's"))))))

(deftest a-query-is-a-get-test
  (with-handler
    (fn [{:keys [handler login]}]
      (ready handler login)
      (let [body (:body (GET handler "/v1/stock" (login :dana)))]
        (is (= {"vanilla" 2} (get (json/read-str body) "stock"))
            "read with no key-fn at all: every key is a string, field names too")
        (testing "and a blanket :key-fn keyword would turn that data into symbols"
          ;; `:key-fn keyword` is safe exactly where keys are *field names*,
          ;; which are known in advance. Here they are flavours, which are not
          ;; — so this is the same rule as the one about values, one level up.
          ;; A map keyed by domain data is a thing to think twice about
          ;; putting on a wire.
          (is (= {:vanilla 2}
                 (:stock (json/read-str body :key-fn keyword)))))))))

;; ---------------------------------------------------------------------------
;; Five status codes, and what each one tells a client to do next
;; ---------------------------------------------------------------------------

(deftest the-whole-refusal-table-test
  (with-handler
    (fn [{:keys [handler login]}]
      (ready handler login)

      (testing "401 — I do not know who you are; try again with a token"
        (let [response (post handler "/v1/sales" nil {:flavour "vanilla"})]
          (is (= 401 (:status response)))
          (is (some? (get-in response [:headers "www-authenticate"]))
              "and RFC 6750 says how to say so")))

      (testing "403 by role — I know who you are, and a better token will not help"
        (is (= 403 (:status (post handler "/v1/sales" (login :rudi) {:flavour "vanilla"})))))

      (testing "403 by ownership — right role, wrong truck"
        (let [response (post handler "/v1/sales" (login :sam) {:flavour "vanilla"})]
          (is (= 403 (:status response)))
          (is (= "Not this truck's driver" (:detail (:parsed response))))))

      (testing "400 — the schema refused it; the domain never saw it"
        (let [response (post handler "/v1/sales" (login :dana) {:flavour "tarmac"})]
          (is (= 400 (:status response)))
          (is (= "malformed" (:error (:parsed response))))))

      (testing "200 — and then 422 once the truck is empty"
        (is (= 200 (:status (post handler "/v1/sales" (login :dana) {:flavour "vanilla"}))))
        (is (= 200 (:status (post handler "/v1/sales" (login :dana) {:flavour "vanilla"}))))
        (let [response (post handler "/v1/sales" (login :dana) {:flavour "vanilla"})]
          (is (= 422 (:status response)))
          (is (= "Sold out" (:detail (:parsed response)))))))))

(deftest four-hundred-and-one-is-not-four-hundred-and-three-test
  (with-handler
    (fn [{:keys [handler login]}]
      (ready handler login)
      (testing "the distinction is whether a different token could ever work"
        (is (= 401 (:status (post handler "/v1/sales" nil {:flavour "vanilla"})))
            "no token — get one")
        (is (= 403 (:status (post handler "/v1/sales" (login :rudi) {:flavour "vanilla"})))
            "a perfectly good token, and it will never be the right one")
        (is (= 200 (:status (post handler "/v1/sales" (login :dana) {:flavour "vanilla"})))
            "the right one")))))

(deftest an-unknown-endpoint-is-404-even-without-a-token-test
  (with-handler
    (fn [{:keys [handler]}]
      (is (= 404 (:status (post handler "/v1/nonsense" nil {}))))
      (testing "because authentication is attached to routes, not to the router"
        (is (= 200 (:status (GET handler "/health" nil))))))))

;; ---------------------------------------------------------------------------
;; The claim in the body is not the claim that counts
;; ---------------------------------------------------------------------------

(deftest the-body-may-not-name-an-actor-test
  (with-handler
    (fn [{:keys [handler login]}]
      (ready handler login)
      (testing "Sam, claiming in the body to be Dana"
        (let [response (post handler "/v1/sales" (login :sam)
                             {:flavour "vanilla"
                              :actor {:type "user" :id "USR-83721"}})]
          (is (= 400 (:status response)))
          (is (= {:data {:actor ["disallowed key"]}} (:detail (:parsed response)))
              "lab 22 closed these maps against 'a bug or an attack'; this is the attack"))))))

;; ---------------------------------------------------------------------------
;; Lab 1's warning, finally testable
;; ---------------------------------------------------------------------------

(deftest not-one-recorded-fact-carries-a-credential-test
  (with-handler
    (fn [{:keys [handler login app]}]
      (let [tokens (login :rudi)]
        (post handler "/v1/driver-assignments" tokens {:driver-id "USR-83721"})
        (post handler "/v1/restocks" tokens {:flavour "vanilla" :quantity 2})

        (let [events (driven/read-stream (:store app) truck-id)
              text   (pr-str events)]
          (is (= 2 (count events)))

          (testing "lab 1: store an opaque actor id, never a token"
            (is (not (str/includes? text (:access_token tokens))))
            (is (not (str/includes? text (:refresh_token tokens))))
            (is (not (str/includes? text "Bearer")))
            (is (nil? (re-find #"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+" text))
                "nothing JWT-shaped anywhere in the stream"))

          (testing "what is kept instead is the subject, and only the subject"
            (is (= [{:type "user" :id "USR-11902"}]
                   (distinct (map (comp :actor :metadata) events)))))

          (testing "which matters because a stream is the one place you cannot redact"
            ;; A bearer credential in append-only storage can never be revoked
            ;; and drags a bundle of personal claims into the store lab 15 had
            ;; to build crypto-shredding for.
            (is (not (str/includes? text "roles")))))))))

;; ---------------------------------------------------------------------------
;; ADR-0020's third layer, at the wire
;; ---------------------------------------------------------------------------

(deftest the-same-query-answers-differently-by-role-test
  (with-handler
    (fn [{:keys [handler login]}]
      (ready handler login)
      (let [as-driver (:parsed (GET handler "/v1/stock" (login :dana)))
            as-depot  (:parsed (GET handler "/v1/stock" (login :rudi)))]
        (is (nil? (:driver as-driver)) "a driver is not shown the roster")
        (is (= "USR-83721" (:driver as-depot)))
        (is (= (:stock as-driver) (:stock as-depot))
            "same underlying events, one of them shaped down")))))

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
                :command/actor {:type "user" :id "USR-83721"}
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

(defn- route-table
  "Walk reitit's route data. A node may carry shared data *and* children —
  which is how `/v1` holds the authentication middleware — so the two are
  told apart rather than assumed."
  []
  (let [walk (fn walk [node prefix]
               (let [[path & more] node
                     full     (str prefix path)
                     data     (when (map? (first more)) (first more))
                     children (filter vector? more)]
                 (if (seq children)
                   (mapcat #(walk % full) children)
                   [[full data]])))]
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

(deftest everything-but-health-is-behind-authentication-test
  (let [[_health v1]  (http/routes {})
        [prefix data] v1]
    (is (= "/v1" prefix))
    (testing "the subtree fails closed, rather than each handler remembering"
      (is (some #{auth/require-authentication} (:middleware data))))
    (testing "and nothing sits outside it except the health check"
      (is (= #{"/health"}
             (into #{} (remove #(str/starts-with? % "/v1/")) (map first (route-table))))
          "a new endpoint added at the top level would be unauthenticated"))))

;; ---------------------------------------------------------------------------
;; One test, one socket
;; ---------------------------------------------------------------------------

(deftest the-authenticated-system-serves-through-real-infrastructure-test
  (testing "one smoke test crosses OIDC, HTTP, the application and real Postgres"
    (let [idp (mock-idp/start!)
          sys (system/start (system/serving
                             (fixture/postgres-system
                              {:oidc (mock-idp/oidc-config idp)}) 0))]
      (try
        (let [port (.getLocalPort (aget (.getConnectors (:server (:http sys))) 0))
              response (post-over-http (str "http://localhost:" port "/v1/restocks")
                                       (mock-idp/login idp :rudi)
                                       {:flavour "vanilla" :quantity 2})]
          (is (= 200 (.statusCode response))))
        (finally (system/stop sys) (mock-idp/stop! idp))))))
