(ns es-lab.task-persistence.service-requests.queries-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.service-requests.queries :as queries]
            [es-lab.task-persistence.test-support :as support]))

(deftest returns-empty-list-when-no-requests
  (let [ctx  (support/make-ports)
        resp ((queries/list-service-requests-handler ctx) {})]
    (is (= 200 (:status resp)))
    (is (= [] (get-in resp [:body :requests])))))

(deftest returns-submitted-requests
  (let [ctx (support/make-ports)
        _   ((commands/submit-service-request-handler ctx)
             {:body-params {:title "Fix door" :description "Room 101"} :headers {}})
        resp ((queries/list-service-requests-handler ctx) {})]
    (is (= 200 (:status resp)))
    (is (= 1 (count (get-in resp [:body :requests]))))
    (is (= "Fix door" (get-in resp [:body :requests 0 :title])))))

(deftest returns-all-requests-newest-first
  (let [ctx (support/make-ports)
        submit #((commands/submit-service-request-handler ctx)
                 {:body-params {:title % :description "desc"} :headers {}})]
    (submit "First")
    (submit "Second")
    (let [resp ((queries/list-service-requests-handler ctx) {})
          titles (mapv :title (get-in resp [:body :requests]))]
      (is (= 2 (count titles))))))
