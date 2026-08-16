(ns registry.server-test
  (:require [clojure.string       :as string]
            [clojure.test         :refer [deftest is use-fixtures]]
            [babashka.http-client :as http]
            [jsonista.core        :as json]
            [registry.server      :as server]
            [registry.decider.runner :as runner]
            [registry.registration.ports.register-port :as register-port]))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(defn make-event-store []
  (let [store (atom {})]
    (reify runner/EventStore
      (load-events  [_ id]     (get @store id []))
      (save-events! [_ id evs] (swap! store update id (fnil into []) evs)))))

(defn make-register-port []
  (reify register-port/RegisterPort
    (company-by-registration-number [_ _] nil)
    (search-by-name                 [_ _] [])))

(defonce server-state (atom nil))
(def ^:dynamic *base-url* nil)
(def ^:dynamic *event-store* nil)

(defn server-fixture [test-fn]
  (let [event-store       (make-event-store)
        address-validator (constantly true)
        srv               (server/start!
                           {:port              0
                            :event-store       event-store
                            :register-port     (make-register-port)
                            :address-validator address-validator})
        port              (-> srv .getConnectors first .getLocalPort)]
    (reset! server-state srv)
    (binding [*base-url*    (str "http://localhost:" port)
              *event-store* event-store]
      (try (test-fn)
           (finally
             (server/stop! srv)
             (reset! server-state nil))))))

(use-fixtures :once server-fixture)

(defn post [path body]
  (http/post (str *base-url* path)
             {:headers {"content-type"  "application/json"
                        "authorization" "Bearer test-token"}
              :body    (json/write-value-as-string body)
              :throw   false}))

(defn get-req [path]
  (http/get (str *base-url* path)
            {:headers {"authorization" "Bearer test-token"}
             :throw   false}))

(defn body [response]
  (json/read-value (:body response) mapper))

(deftest health-returns-ok
  (let [response (http/get (str *base-url* "/health") {:throw false})]
    (is (= 200 (:status response)))
    (is (= "ok" (:body response)))))

(deftest openapi-json-accessible-without-auth
  (let [response (http/get (str *base-url* "/openapi.json") {:throw false})
        spec     (body response)]
    (is (= 200 (:status response)))
    (is (string/starts-with? (:openapi spec) "3."))))

(deftest openapi-submit-endpoint-has-required-fields
  (let [spec   (body (http/get (str *base-url* "/openapi.json") {:throw false}))
        schema (get-in spec [:paths
                             (keyword "/api/v1/registration-applications/{id}/submit")
                             :post :requestBody :content
                             (keyword "application/json") :schema])
        req    (set (:required schema))]
    (is (= #{"company-name" "proposed-directors" "registered-office-address"} req))))

(deftest openapi-submit-endpoint-has-executable-example
  (let [spec    (body (http/get (str *base-url* "/openapi.json") {:throw false}))
        example (get-in spec [:paths
                              (keyword "/api/v1/registration-applications/{id}/submit")
                              :post :requestBody :content
                              (keyword "application/json") :example])]
    (is (not (string/blank? (:company-name example))))
    (is (<= 2 (count (:proposed-directors example))))
    (is (not (string/blank? (get-in example [:registered-office-address :address-line-1]))))))

(deftest openapi-contains-register-endpoints
  (let [spec  (body (http/get (str *base-url* "/openapi.json") {:throw false}))
        paths (:paths spec)]
    (is (contains? paths (keyword "/api/v1/register")))
    (is (contains? paths (keyword "/api/v1/register/{registration-number}")))))

(deftest swagger-ui-accessible-without-auth
  (let [response (http/get (str *base-url* "/swagger-ui") {:throw false})]
    (is (= 200 (:status response)))))

(deftest unauthenticated-returns-401
  (let [response (http/post (str *base-url* "/api/v1/registration-applications")
                            {:headers {"content-type" "application/json"}
                             :body    "{}"
                             :throw   false})]
    (is (= 401 (:status response)))))

(deftest create-registration-application-returns-201
  (let [response (post "/api/v1/registration-applications" {})]
    (is (= 201 (:status response)))
    (is (some? (:application-id (body response))))))

(deftest submit-valid-application-returns-200
  (let [create-resp (post "/api/v1/registration-applications" {})
        app-id      (:application-id (body create-resp))
        submit-resp (post (str "/api/v1/registration-applications/" app-id "/submit")
                          {:company-name "Acme Ltd"
                           :proposed-directors [{:id "d-1" :name "Jane Smith"
                                                 :natural-person true :identity-verified true}
                                                {:id "d-2" :name "Alice Jones"
                                                 :natural-person true :identity-verified true}]
                           :registered-office-address {:address-line-1 "1 Main St"
                                                       :city "Dublin" :country "IE"}})]
    (is (= 200 (:status submit-resp)))))

(deftest submit-with-one-director-returns-422
  (let [create-resp (post "/api/v1/registration-applications" {})
        app-id      (:application-id (body create-resp))
        submit-resp (post (str "/api/v1/registration-applications/" app-id "/submit")
                          {:company-name "Acme Ltd"
                           :proposed-directors [{:id "d-1" :name "Jane Smith"
                                                 :natural-person true :identity-verified true}]
                           :registered-office-address {:address-line-1 "1 Main St"
                                                       :city "Dublin" :country "IE"}})]
    (is (= 422 (:status submit-resp)))
    (is (= "at-least-two-proposed-directors-required"
           (:error (body submit-resp))))))

(deftest correlation-id-echoed-in-response
  (let [response (http/post (str *base-url* "/api/v1/registration-applications")
                            {:headers {"content-type"      "application/json"
                                       "authorization"     "Bearer test-token"
                                       "x-correlation-id" "test-trace-id-001"}
                             :body    "{}"
                             :throw   false})]
    (is (= "test-trace-id-001"
           (get-in response [:headers "x-correlation-id"])))))

(deftest unknown-route-returns-404
  (let [response (get-req "/api/v1/does-not-exist")]
    (is (= 404 (:status response)))))

(deftest command-on-terminal-aggregate-returns-422
  (let [create-resp (post "/api/v1/registration-applications" {})
        app-id      (:application-id (body create-resp))
        _           (post (str "/api/v1/registration-applications/" app-id "/submit")
                          {:company-name "Acme Ltd"
                           :proposed-directors [{:id "d-1" :name "Jane Smith"
                                                 :natural-person true :identity-verified true}
                                                {:id "d-2" :name "Alice Jones"
                                                 :natural-person true :identity-verified true}]
                           :registered-office-address {:address-line-1 "1 Main St"
                                                       :city "Dublin" :country "IE"}})
        _           (post (str "/api/v1/registration-applications/" app-id "/withdraw") {})
        response    (post (str "/api/v1/registration-applications/" app-id "/begin-examination") {})]
    (is (= 422 (:status response)))))
