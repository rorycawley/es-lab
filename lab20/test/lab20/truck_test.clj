(ns lab20.truck-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab20.truck :as truck]))

(deftest load-quantity-is-a-domain-invariant-test
  (doseq [quantity [0 -1 1.5 nil]]
    (is (= :invalid-quantity
           (:reason
            (ex-data
             (try
               (truck/decide {:command/type :load-truck
                              :data {:flavour "vanilla" :quantity quantity}}
                             truck/initial-state)
               (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ensure-stock-can-legitimately-produce-no-fact-test
  (let [state {"vanilla" 3}]
    (is (= []
           (truck/decide {:command/type :ensure-stock
                          :data {:flavour "vanilla" :quantity 3}}
                         state)))
    (is (= [{:event/type :truck-loaded
             :data {:flavour "vanilla" :quantity 2}}]
           (truck/decide {:command/type :ensure-stock
                          :data {:flavour "vanilla" :quantity 5}}
                         state)))))

(deftest selling-the-last-cone-preserves-fact-order-test
  (is (= [:flavour-sold :stock-depleted]
         (mapv :event/type
               (truck/decide {:command/type :buy-flavour
                              :data {:flavour "vanilla"}}
                             {"vanilla" 1})))))

(deftest unknown-semantics-fail-closed-test
  (testing "unknown commands and historical events are never silent no-ops"
    (is (thrown? clojure.lang.ExceptionInfo
                 (truck/decide {:command/type :retire-truck} {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (truck/replay [{:event/type :truck-retired :data {}}])))))
