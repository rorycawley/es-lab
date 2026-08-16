(ns registry.registration.api.handlers-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [registry.decider.runner                              :as runner]
            [registry.registration.api.handlers                  :as handlers]))

;; =============================================================================
;; In-memory event store — no HTTP, no network, tests run fast
;; =============================================================================

(defn make-event-store
  ([] (make-event-store (constantly nil)))
  ([save-events-error]
   (let [store (atom {})]
     (reify runner/EventStore
       (load-events  [_ id]     (get @store id []))
       (save-events! [_ id evs]
         (if-let [error (save-events-error evs)]
           (throw error)
           (swap! store update id (fnil into []) evs)))))))

(defn- duplicate-name-event-store []
  (make-event-store
   (fn [events]
     (when (some #(= :company-registered (:event/type %)) events)
       (ex-info "Company name already exists in register"
                {:reason :business-rule-failed
                 :error  :company-name-already-exists-in-register})))))

(def ^:dynamic *store* nil)

(defn store-fixture [f]
  (binding [*store* (make-event-store)]
    (f)))

(use-fixtures :each store-fixture)

;; =============================================================================
;; Request builder helpers
;; =============================================================================

(defn- base-request []
  {:identity       {:user-id "test-user"}
   :correlation-id "test-corr"})

(defn- with-path-params [req params]
  (assoc req :path-params params))

(defn- with-body [req body]
  (assoc req :body-params body))

(defn- valid-directors []
  [{:id "d-1" :name "Jane Smith"  :natural-person true :identity-verified true}
   {:id "d-2" :name "Alice Jones" :natural-person true :identity-verified true}])

(defn- valid-address []
  {:address-line-1 "1 Main St" :city "Dublin" :country "IE"})

;; =============================================================================
;; create-registration-application
;; =============================================================================

(deftest create-returns-201-with-application-id
  (let [handler  (handlers/create-registration-application *store*)
        response (handler (base-request))]
    (is (= 201 (:status response)))
    (is (string? (get-in response [:body :application-id])))))

(deftest create-second-application-gets-distinct-id
  (let [handler (handlers/create-registration-application *store*)
        id-1    (get-in (handler (base-request)) [:body :application-id])
        id-2    (get-in (handler (base-request)) [:body :application-id])]
    (is (not= id-1 id-2))))

;; =============================================================================
;; submit-registration-application
;; =============================================================================

(defn- create-app! []
  (let [handler  (handlers/create-registration-application *store*)
        response (handler (base-request))]
    (get-in response [:body :application-id])))

(deftest submit-valid-application-returns-200
  (let [app-id  (create-app!)
        handler (handlers/submit-registration-application *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})
                             (with-body {:company-name              "Acme Ltd"
                                         :proposed-directors        (valid-directors)
                                         :registered-office-address (valid-address)})))]
    (is (= 200 (:status resp)))))

(deftest submit-with-one-director-returns-422
  (let [app-id  (create-app!)
        handler (handlers/submit-registration-application *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})
                             (with-body {:company-name              "Acme Ltd"
                                         :proposed-directors        [(first (valid-directors))]
                                         :registered-office-address (valid-address)})))]
    (is (= 422 (:status resp)))
    (is (= "at-least-two-proposed-directors-required" (get-in resp [:body :error])))))

(deftest submit-with-blank-company-name-returns-422
  (let [app-id  (create-app!)
        handler (handlers/submit-registration-application *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})
                             (with-body {:company-name              ""
                                         :proposed-directors        (valid-directors)
                                         :registered-office-address (valid-address)})))]
    (is (= 422 (:status resp)))))

;; =============================================================================
;; begin-examination
;; =============================================================================

(defn- submit-app! [app-id]
  (let [handler (handlers/submit-registration-application *store*)]
    (handler (-> (base-request)
                 (with-path-params {:id app-id})
                 (with-body {:company-name              "Acme Ltd"
                             :proposed-directors        (valid-directors)
                             :registered-office-address (valid-address)})))))

(deftest begin-examination-after-submit-returns-200
  (let [app-id  (create-app!)
        _       (submit-app! app-id)
        handler (handlers/begin-examination *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})))]
    (is (= 200 (:status resp)))))

(deftest begin-examination-on-draft-returns-422
  (let [app-id  (create-app!)
        handler (handlers/begin-examination *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})))]
    (is (= 422 (:status resp)))))

;; =============================================================================
;; approve-registration-application
;; =============================================================================

(defn- begin-examination! [app-id]
  (let [handler (handlers/begin-examination *store*)]
    (handler (-> (base-request)
                 (with-path-params {:id app-id})))))

(deftest approve-returns-200
  (let [app-id (create-app!)]
    (submit-app! app-id)
    (begin-examination! app-id)
    (let [handler (handlers/approve-registration-application *store* (constantly true))
          resp    (handler (-> (base-request)
                               (with-path-params {:id app-id})))]
      (is (= 200 (:status resp))))))

(deftest approve-with-duplicate-name-returns-422
  (binding [*store* (duplicate-name-event-store)]
    (let [app-id (create-app!)]
      (submit-app! app-id)
      (begin-examination! app-id)
      (let [handler (handlers/approve-registration-application *store* (constantly true))
            resp    (handler (-> (base-request)
                                 (with-path-params {:id app-id})))]
        (is (= 422 (:status resp)))
        (is (= "company-name-already-exists-in-register" (get-in resp [:body :error])))))))

(deftest approve-with-invalid-address-returns-422
  (let [app-id (create-app!)]
    (submit-app! app-id)
    (begin-examination! app-id)
    (let [handler (handlers/approve-registration-application *store* (constantly false))
          resp    (handler (-> (base-request)
                               (with-path-params {:id app-id})))]
      (is (= 422 (:status resp)))
      (is (= "registered-office-address-not-valid" (get-in resp [:body :error]))))))

;; =============================================================================
;; reject-registration-application
;; =============================================================================

(deftest reject-returns-200
  (let [app-id (create-app!)]
    (submit-app! app-id)
    (begin-examination! app-id)
    (let [handler (handlers/reject-registration-application *store*)
          resp    (handler (-> (base-request)
                               (with-path-params {:id app-id})
                               (with-body {:rejection-reason "Name is deceptive under §8"})))]
      (is (= 200 (:status resp))))))

(deftest reject-with-blank-reason-returns-422
  (let [app-id (create-app!)]
    (submit-app! app-id)
    (begin-examination! app-id)
    (let [handler (handlers/reject-registration-application *store*)
          resp    (handler (-> (base-request)
                               (with-path-params {:id app-id})
                               (with-body {:rejection-reason ""})))]
      (is (= 422 (:status resp)))
      (is (= "rejection-reason-required" (get-in resp [:body :error]))))))

;; =============================================================================
;; withdraw-registration-application
;; =============================================================================

(deftest withdraw-from-submitted-returns-200
  (let [app-id  (create-app!)
        _       (submit-app! app-id)
        handler (handlers/withdraw-registration-application *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})))]
    (is (= 200 (:status resp)))))

(deftest withdraw-from-under-examination-returns-200
  (let [app-id  (create-app!)
        _       (submit-app! app-id)
        _       (begin-examination! app-id)
        handler (handlers/withdraw-registration-application *store*)
        resp    (handler (-> (base-request)
                             (with-path-params {:id app-id})))]
    (is (= 200 (:status resp)))))
