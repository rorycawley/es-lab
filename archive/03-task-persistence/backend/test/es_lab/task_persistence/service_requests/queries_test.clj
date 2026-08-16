(ns es-lab.task-persistence.service-requests.queries-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.service-requests.queries :as queries]
            [es-lab.task-persistence.test-support :as ts]))

(defn- submit [ctx title description]
  (let [handler (commands/submit-service-request-handler ctx)]
    (handler {:headers {} :body-params {:title title :description description}})))

(deftest list-returns-200-with-requests-array
  ;; AC-04-06
  (let [ctx     (ts/make-ports)
        handler (queries/list-service-requests-handler ctx)
        resp    (handler {})]
    (is (= 200 (:status resp)))
    (is (vector? (get-in resp [:body :requests])))))

(deftest search-returns-empty-array-when-no-matches
  ;; AC-08-07
  (let [ctx     (ts/make-ports)
        handler (queries/search-service-requests-handler ctx)
        resp    (handler {:body-params {:query "xyzzy"}})]
    (is (= 200 (:status resp)))
    (is (= [] (get-in resp [:body :requests])))))

(deftest search-rejects-missing-query-field
  ;; AC-08-08
  (let [ctx     (ts/make-ports)
        handler (queries/search-service-requests-handler ctx)
        resp    (handler {:body-params {}})]
    (is (= 422 (:status resp)))
    (is (contains? (:body resp) :error))))

(deftest search-rejects-blank-query
  ;; AC-08-09
  (let [ctx     (ts/make-ports)
        handler (queries/search-service-requests-handler ctx)]
    (doseq [q ["" "   "]]
      (let [resp (handler {:body-params {:query q}})]
        (is (= 422 (:status resp)))
        (is (contains? (:body resp) :error))))))

(deftest search-rejects-non-string-query
  ;; AC-08-09
  (let [ctx     (ts/make-ports)
        handler (queries/search-service-requests-handler ctx)]
    (doseq [q [42 nil true []]]
      (let [resp (handler {:body-params {:query q}})]
        (is (= 422 (:status resp)))
        (is (contains? (:body resp) :error))))))

(deftest search-trims-whitespace-from-query
  ;; AC-08-10
  (let [ctx            (ts/make-ports)
        search-handler (queries/search-service-requests-handler ctx)]
    (submit ctx "Broken printer" "Paper jam on floor 2")
    (let [trimmed (search-handler {:body-params {:query "printer"}})
          padded  (search-handler {:body-params {:query "  printer  "}})]
      (is (= (get-in trimmed [:body :requests])
             (get-in padded  [:body :requests]))))))
