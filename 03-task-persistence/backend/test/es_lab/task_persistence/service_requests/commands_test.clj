(ns es-lab.task-persistence.service-requests.commands-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.test-support :as support]))

(defn- handle [ctx req]
  ((commands/submit-service-request-handler ctx) req))

(deftest returns-200-with-saved-request
  (let [ctx  (support/make-ports)
        resp (handle ctx {:body-params {:title "Fix door" :description "Room 101 handle broken"}
                          :headers     {}})]
    (is (= 200 (:status resp)))
    (is (= "Fix door" (get-in resp [:body :title])))
    (is (= "demo-user" (get-in resp [:body :submitted_by])))
    (is (string? (get-in resp [:body :request_id])))))

(deftest uses-x-user-id-header
  (let [ctx  (support/make-ports)
        resp (handle ctx {:body-params {:title "Test" :description "Details"}
                          :headers     {"x-user-id" "alice"}})]
    (is (= "alice" (get-in resp [:body :submitted_by])))))

(deftest returns-422-when-title-blank
  (let [ctx  (support/make-ports)
        resp (handle ctx {:body-params {:title "" :description "Details"}
                          :headers     {}})]
    (is (= 422 (:status resp)))))

(deftest returns-422-when-description-missing
  (let [ctx  (support/make-ports)
        resp (handle ctx {:body-params {:title "Title"}
                          :headers     {}})]
    (is (= 422 (:status resp)))))

(deftest records-audit-event
  (let [ctx (support/make-ports)
        _   (handle ctx {:body-params {:title "Fix door" :description "Details"}
                         :headers     {"x-user-id" "bob"}})]
    (is (= 1 (count (-> ctx :audit-port :!store deref))))
    (is (= "bob" (-> ctx :audit-port :!store deref first :actor)))
    (is (= "submit-service-request" (-> ctx :audit-port :!store deref first :action)))))
