(ns es-lab.task-persistence.service-requests.queries-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.service-requests.port :as sr]
            [es-lab.task-persistence.service-requests.queries :as queries]
            [spy.protocol :as protocol]))

(defn- make-ctx [requests]
  {:service-request-port (protocol/mock sr/ServiceRequestPort
                           (list-all [_] requests))})

(defn- list-requests [ctx]
  ((queries/list-service-requests-handler ctx) {}))

(def ^:private stub-request
  {:request_id   "01955f3d-0000-7000-8000-000000000001"
   :submitted_by "demo-user"
   :title        "Fix door"
   :description  "Room 101"
   :status       "submitted"
   :created_at   "2026-01-01T00:00:00Z"
   :updated_at   "2026-01-01T00:00:00Z"})

(deftest returns-200-with-empty-list
  (let [resp (list-requests (make-ctx []))]
    (is (= 200 (:status resp)))
    (is (= [] (get-in resp [:body :requests])))))

(deftest returns-submitted-requests
  (let [resp (list-requests (make-ctx [stub-request]))]
    (is (= 200 (:status resp)))
    (is (= 1 (count (get-in resp [:body :requests]))))
    (is (= "Fix door" (get-in resp [:body :requests 0 :title])))))

(deftest returns-all-requests
  (let [r1   (assoc stub-request :request_id "01955f3d-0000-7000-8000-000000000001" :title "First")
        r2   (assoc stub-request :request_id "01955f3d-0000-7000-8000-000000000002" :title "Second")
        resp (list-requests (make-ctx [r1 r2]))]
    (is (= 2 (count (get-in resp [:body :requests]))))))
