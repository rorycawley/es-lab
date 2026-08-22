(ns lab31.system-test
  (:require [clojure.test :refer [deftest is]]
            [lab31.performance.search :as search]
            [lab31.performance.system :as system]
            [lab31.performance.workload :as workload]))

(deftest compute-and-boundary-designs-answer-the-same-question-test
  (let [entities      (workload/corpus 100)
        registrations [(workload/registration-number 99)
                       "REG-NOT-PRESENT"]
        index         (search/build-index entities)
        gateway       (system/simulated-gateway
                       index
                       (partial search/indexed-many index)
                       0)
        local         (system/local-journey
                       (partial search/indexed-many index)
                       registrations)
        chatty        (system/chatty-journey gateway registrations)
        batched       (system/batched-journey gateway registrations)]
    (is (= (:found local) (:found chatty) (:found batched)))
    (is (= 0 (:round-trips local)))
    (is (= 2 (:round-trips chatty)))
    (is (= 1 (:round-trips batched)))
    (is (= {:one 2 :many 1} @(:calls gateway)))))
