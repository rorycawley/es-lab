(ns registry.registration.api.consumer-contract-test
  (:require [babashka.http-client                         :as http]
            [clojure.test                                 :refer [deftest is use-fixtures]]
            [jsonista.core                                :as json]
            [registry.decider.runner                      :as runner]
            [registry.registration.ports.register-port    :as register-port]
            [registry.server                              :as server]))

;; Pact-style provider verification without a broker:
;; each interaction records the consumer-visible provider state, request, and
;; response expectation, then verifies the running Ring provider over HTTP.

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(def fixed-company
  {:register/registration_number "IE-2026-000001"
   :register/company_name        "Acme Registry Ltd"
   :register/registered_at       "2026-05-26T10:15:30Z"})

(defn- make-event-store []
  (let [store (atom {})]
    (reify runner/EventStore
      (load-events  [_ id]     (get @store id []))
      (save-events! [_ id evs] (swap! store update id (fnil into []) evs)))))

(defn- make-register-port []
  (reify register-port/RegisterPort
    (company-by-registration-number [_ registration-number]
      (when (= registration-number (:register/registration_number fixed-company))
        fixed-company))
    (search-by-name [_ name-fragment]
      (if (re-find (re-pattern (str "(?i)" name-fragment))
                   (:register/company_name fixed-company))
        [fixed-company]
        []))))

(defonce server-state (atom nil))
(def ^:dynamic *base-url* nil)

(defn- server-fixture [test-fn]
  (let [srv  (server/start!
              {:port              0
               :event-store       (make-event-store)
               :register-port     (make-register-port)
               :address-validator (constantly true)})
        port (-> srv .getConnectors first .getLocalPort)]
    (reset! server-state srv)
    (binding [*base-url* (str "http://localhost:" port)]
      (try
        (test-fn)
        (finally
          (server/stop! srv)
          (reset! server-state nil))))))

(use-fixtures :once server-fixture)

(defn- parse-body [response]
  (json/read-value (:body response) mapper))

(defn- send-request [{:keys [method path body]}]
  (case method
    :get
    (http/get (str *base-url* path)
              {:headers {"authorization" "Bearer consumer-contract-token"}
               :throw   false})

    :post
    (http/post (str *base-url* path)
               {:headers {"content-type"  "application/json"
                          "authorization" "Bearer consumer-contract-token"}
                :body    (json/write-value-as-string (or body {}))
                :throw   false})))

(defn- valid-application-body [company-name]
  {:company-name company-name
   :proposed-directors
   [{:id "director-1" :name "Jane Smith" :natural-person true :identity-verified true}
    {:id "director-2" :name "Alice Jones" :natural-person true :identity-verified true}]
   :registered-office-address
   {:address-line-1 "1 Main Street"
    :city           "Dublin"
    :country        "IE"}})

(defn- create-application! []
  (-> (send-request {:method :post
                     :path   "/api/v1/registration-applications"
                     :body   {}})
      parse-body
      :application-id))

(defn- submit-application! [application-id company-name]
  (send-request {:method :post
                 :path   (str "/api/v1/registration-applications/" application-id "/submit")
                 :body   (valid-application-body company-name)}))

(defn- begin-examination! [application-id]
  (send-request {:method :post
                 :path   (str "/api/v1/registration-applications/" application-id "/begin-examination")
                 :body   {}}))

(defn- only-keys? [expected-keys body]
  (= expected-keys (set (keys body))))

(defn- application-created-body? [body]
  (and (only-keys? #{:application-id} body)
       (string? (:application-id body))
       (seq (:application-id body))))

(defn- ok-body? [body]
  (= {:ok true} body))

(defn- error-body? [body]
  (and (only-keys? #{:error} body)
       (string? (:error body))
       (seq (:error body))))

(defn- company-body? [body]
  (and (only-keys? #{:registration-number :company-name :registered-at} body)
       (= "IE-2026-000001" (:registration-number body))
       (= "Acme Registry Ltd" (:company-name body))
       (string? (:registered-at body))))

(defn- company-search-body? [body]
  (and (only-keys? #{:companies} body)
       (= 1 (count (:companies body)))
       (company-body? (first (:companies body)))))

(def consumer-interactions
  [{:name           "consumer creates a draft registration application"
    :provider-state "no prior application is required"
    :request        {:method :post
                     :path   "/api/v1/registration-applications"
                     :body   {}}
    :response       {:status 201
                     :body   application-created-body?}}

   {:name           "consumer submits a valid draft registration application"
    :provider-state "a draft registration application exists"
    :given          (fn []
                      {:application-id (create-application!)})
    :request        (fn [{:keys [application-id]}]
                      {:method :post
                       :path   (str "/api/v1/registration-applications/" application-id "/submit")
                       :body   (valid-application-body "Consumer Submit Ltd")})
    :response       {:status 200
                     :body   ok-body?}}

   {:name           "consumer receives a contract error for too few proposed directors"
    :provider-state "a draft registration application exists"
    :given          (fn []
                      {:application-id (create-application!)})
    :request        (fn [{:keys [application-id]}]
                      {:method :post
                       :path   (str "/api/v1/registration-applications/" application-id "/submit")
                       :body   (assoc (valid-application-body "Consumer Invalid Ltd")
                                      :proposed-directors
                                      [{:id "director-1"
                                        :name "Jane Smith"
                                        :natural-person true
                                        :identity-verified true}])})
    :response       {:status 422
                     :body   error-body?}}

   {:name           "consumer begins examination for a submitted application"
    :provider-state "a submitted registration application exists"
    :given          (fn []
                      (let [application-id (create-application!)]
                        (submit-application! application-id "Consumer Examination Ltd")
                        {:application-id application-id}))
    :request        (fn [{:keys [application-id]}]
                      {:method :post
                       :path   (str "/api/v1/registration-applications/"
                                    application-id
                                    "/begin-examination")
                       :body   {}})
    :response       {:status 200
                     :body   ok-body?}}

   {:name           "consumer approves an application and registers a company"
    :provider-state "an application is under examination"
    :given          (fn []
                      (let [application-id (create-application!)]
                        (submit-application! application-id "Consumer Approval Ltd")
                        (begin-examination! application-id)
                        {:application-id application-id}))
    :request        (fn [{:keys [application-id]}]
                      {:method :post
                       :path   (str "/api/v1/registration-applications/" application-id "/approve")
                       :body   {}})
    :response       {:status 200
                     :body   ok-body?}}

   {:name           "consumer rejects an application with a reason"
    :provider-state "an application is under examination"
    :given          (fn []
                      (let [application-id (create-application!)]
                        (submit-application! application-id "Consumer Rejection Ltd")
                        (begin-examination! application-id)
                        {:application-id application-id}))
    :request        (fn [{:keys [application-id]}]
                      {:method :post
                       :path   (str "/api/v1/registration-applications/" application-id "/reject")
                       :body   {:rejection-reason "Name is misleading"}})
    :response       {:status 200
                     :body   ok-body?}}

   {:name           "consumer looks up a registered company"
    :provider-state "a registered company exists in the Register"
    :request        {:method :get
                     :path   "/api/v1/register/IE-2026-000001"}
    :response       {:status 200
                     :body   company-body?}}

   {:name           "consumer searches the Register by company name"
    :provider-state "a registered company exists in the Register"
    :request        {:method :get
                     :path   "/api/v1/register?name=Acme"}
    :response       {:status 200
                     :body   company-search-body?}}])

(deftest consumer-contracts-are-honoured-by-provider
  (doseq [{:keys [name given request response]} consumer-interactions]
    (let [state        (if given (given) {})
          req          (if (fn? request) (request state) request)
          http-resp    (send-request req)
          parsed-body  (parse-body http-resp)
          expected-body (:body response)]
      (is (= (:status response) (:status http-resp)) name)
      (is (expected-body parsed-body) name))))
