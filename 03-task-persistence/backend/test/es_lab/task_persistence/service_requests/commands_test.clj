(ns es-lab.task-persistence.service-requests.commands-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.service-requests.port :as sr]
            [spy.core :as spy]
            [spy.protocol :as protocol]))

(def ^:private stub-saved
  {:request_id   "01955f3d-0000-7000-8000-000000000001"
   :submitted_by "demo-user"
   :title        "Fix door"
   :description  "Room 101 handle broken"
   :status       "submitted"
   :created_at   "2026-01-01T00:00:00Z"
   :updated_at   "2026-01-01T00:00:00Z"})

(defn- make-ctx
  ([] (make-ctx stub-saved))
  ([saved]
   {:service-request-port (protocol/mock sr/ServiceRequestPort
                            (save! [_ _] saved))
    :audit-port           (protocol/mock audit/AuditPort
                            (record! [_ _] nil))}))

(defn- handle [ctx req]
  ((commands/submit-service-request-handler ctx) req))

(deftest returns-200-with-saved-request
  (let [ctx  (make-ctx)
        resp (handle ctx {:body-params {:title "Fix door" :description "Room 101 handle broken"}
                          :headers     {}})]
    (is (= 200 (:status resp)))
    (is (= "Fix door" (get-in resp [:body :title])))
    (is (= "demo-user" (get-in resp [:body :submitted_by])))
    (is (string? (get-in resp [:body :request_id])))))

(deftest uses-x-user-id-header
  (let [ctx  (make-ctx (assoc stub-saved :submitted_by "alice"))
        resp (handle ctx {:body-params {:title "Test" :description "Details"}
                          :headers     {"x-user-id" "alice"}})]
    (is (= "alice" (get-in resp [:body :submitted_by])))))

(deftest returns-422-when-title-blank
  (let [ctx  (make-ctx)
        resp (handle ctx {:body-params {:title "" :description "Details"}
                          :headers     {}})]
    (is (= 422 (:status resp)))
    (is (spy/not-called? (:save! (protocol/spies (:service-request-port ctx)))))))

(deftest returns-422-when-description-missing
  (let [ctx  (make-ctx)
        resp (handle ctx {:body-params {:title "Title"}
                          :headers     {}})]
    (is (= 422 (:status resp)))
    (is (spy/not-called? (:save! (protocol/spies (:service-request-port ctx)))))))

(deftest records-audit-event
  (let [ctx        (make-ctx (assoc stub-saved :submitted_by "bob"))
        _          (handle ctx {:body-params {:title "Fix door" :description "Details"}
                                :headers     {"x-user-id" "bob"}})
        record-spy (:record! (protocol/spies (:audit-port ctx)))]
    (is (spy/called-once? record-spy))
    ; spy/calls returns a seq of raw arg-seqs, not {:args [...]} maps.
    ; For (record! [this event]) the call is (this event), so second = event.
    (let [event (-> (spy/calls record-spy) first second)]
      (is (= "bob" (:actor event)))
      (is (= "submit-service-request" (:action event))))))
