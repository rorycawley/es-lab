(ns registry.registration.api.handlers
  (:require [registry.decider.runner                              :as runner]
            [registry.registration.decider                       :as decider]
            [registry.registration.ports.register-port           :as register-port]))

;; =============================================================================
;; HTTP handlers — thin layer
;; Each handler: extract from request, build command, call runner, return response
;; No business logic. No Decider knowledge. No infrastructure.
;; =============================================================================

(defn- correlation-id [req] (:correlation-id req))
(defn- user-id [req] (get-in req [:identity :user-id]))

(defn- ok [body]       {:status 200 :body body})
(defn- created [body]  {:status 201 :body body})
(defn- unprocessable [error] {:status 422 :body {:error (name error)}})

(defn- result->response [result created?]
  (cond
    (:error result)           (unprocessable (:error result))
    (and created? (:ok result)) (created {:application-id (:aggregate-id result)})
    (:ok result)              (ok {:ok true})))

(defn create-registration-application
  "POST /api/v1/registration-applications"
  [event-store]
  (fn [request]
    (-> (runner/create!
         decider/registration-decider
         event-store
         {:command/type :create-registration-application
          :applicant-id (user-id request)}
         (correlation-id request))
        (result->response true))))

(defn submit-registration-application
  "POST /api/v1/registration-applications/:id/submit"
  [event-store]
  (fn [request]
    (let [body   (:body-params request)
          app-id (get-in request [:path-params :id])
          dirs   (mapv (fn [d] {:id                 (:id d)
                                :name               (:name d)
                                :natural-person?    (boolean (:natural-person d))
                                :identity-verified? (boolean (:identity-verified d))})
                       (:proposed-directors body))]
      (-> (runner/execute!
           decider/registration-decider
           event-store
           app-id
           {:command/type              :submit-registration-application
            :applicant-id              (user-id request)
            :company-name              (:company-name body)
            :proposed-directors        dirs
            :registered-office-address (:registered-office-address body)}
           (correlation-id request))
          (result->response false)))))

(defn begin-examination
  "POST /api/v1/registration-applications/:id/begin-examination"
  [event-store]
  (fn [request]
    (-> (runner/execute!
         decider/registration-decider
         event-store
         (get-in request [:path-params :id])
         {:command/type :begin-examination
          :examiner-id  (user-id request)}
         (correlation-id request))
        (result->response false))))

(defn approve-registration-application
  "POST /api/v1/registration-applications/:id/approve
   Injects BR-010 (address validity) check.
   All other rules (BR-002 through BR-006) are re-checked against the submitted
   state — the approval body carries only registrar-id.
   BR-008 is enforced by the event store transaction when company-registered is
   appended."
  [event-store address-validator]
  (fn [request]
    (-> (runner/execute!
         decider/registration-decider
         event-store
         (get-in request [:path-params :id])
         {:command/type     :approve-registration-application
          :registrar-id     (user-id request)
          ;; BR-010 injected — address validated by address service
          :address-valid-fn address-validator}
         (correlation-id request))
        (result->response false))))

(defn reject-registration-application
  "POST /api/v1/registration-applications/:id/reject"
  [event-store]
  (fn [request]
    (let [body (:body-params request)]
      (-> (runner/execute!
           decider/registration-decider
           event-store
           (get-in request [:path-params :id])
           {:command/type     :reject-registration-application
            :registrar-id     (user-id request)
            :rejection-reason (:rejection-reason body)}
           (correlation-id request))
          (result->response false)))))

(defn withdraw-registration-application
  "POST /api/v1/registration-applications/:id/withdraw"
  [event-store]
  (fn [request]
    (-> (runner/execute!
         decider/registration-decider
         event-store
         (get-in request [:path-params :id])
         {:command/type :withdraw-registration-application
          :applicant-id (user-id request)}
         (correlation-id request))
        (result->response false))))

(defn get-company
  "GET /api/v1/register/:registration-number"
  [register-port]
  (fn [request]
    (let [reg-number (get-in request [:path-params :registration-number])
          company    (register-port/company-by-registration-number register-port reg-number)]
      (if company
        (ok {:registration-number (:register/registration_number company)
             :company-name        (:register/company_name company)
             :registered-at       (str (:register/registered_at company))})
        {:status 404 :body {:error "company-not-found"}}))))

(defn search-companies
  "GET /api/v1/register?name=..."
  [register-port]
  (fn [request]
    (let [name-fragment (get-in request [:query-params "name"] "")
          companies     (register-port/search-by-name register-port name-fragment)]
      (ok {:companies (mapv (fn [c]
                              {:registration-number (:register/registration_number c)
                               :company-name        (:register/company_name c)
                               :registered-at       (str (:register/registered_at c))})
                            companies)}))))
