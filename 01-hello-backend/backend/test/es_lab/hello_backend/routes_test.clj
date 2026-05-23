(ns es-lab.hello-backend.routes-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.hello-backend.routes :refer [health-handler]]))

(deftest health-handler-returns-200
  (is (= 200 (:status (health-handler {})))))

(deftest health-handler-body-has-status-ok
  (is (= "ok" (:status (:body (health-handler {}))))))

(deftest health-handler-body-has-version
  (is (string? (:version (:body (health-handler {}))))))
