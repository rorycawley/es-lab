(ns lab31.workload-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab31.performance.search :as search]
            [lab31.performance.workload :as workload]))

(deftest proof-workload-is-held-out-test
  (let [size   20000
        tuning (set (workload/tuning-keys size))
        proof  (workload/proof-keys size)]
    (is (= 12 (count proof)))
    (is (empty? (filter tuning proof)))
    (is (= "REG-NOT-PRESENT" (last proof)))
    (is (= (conj (workload/proof-entity-ids size) nil)
           (workload/proof-expected-ids size)))))

(deftest both-implementations-preserve-semantics-test
  (let [entities (workload/corpus 20000)
        index    (search/build-index entities)]
    (doseq [registrations [(workload/tuning-keys 20000)
                           (workload/proof-keys 20000)]]
      (testing (str "workload " registrations)
        (is (= (search/scan-many entities registrations)
               (search/indexed-many index registrations)))))))
