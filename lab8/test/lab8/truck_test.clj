(ns lab8.truck-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab8.truck :as truck]))

(defn- buy [flavour] {:command/type :buy-flavour :data {:flavour flavour}})
(defn- load-truck [flavour quantity]
  {:command/type :load-truck :data {:flavour flavour :quantity quantity}})

(deftest decide-produces-one-event-test
  (testing "plenty in stock: one fact"
    (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}]
           (truck/decide (buy "vanilla") {"vanilla" 3})))))

(deftest decide-produces-many-events-test
  (testing "the last cone is two facts, in the order they became true"
    (is (= [{:event/type :flavour-sold   :data {:flavour "vanilla"}}
            {:event/type :stock-depleted :data {:flavour "vanilla"}}]
           (truck/decide (buy "vanilla") {"vanilla" 1})))))

(deftest decide-produces-zero-events-test
  (testing "loading nothing is not a fact, and not an error either"
    (is (= [] (truck/decide (load-truck "vanilla" 0) truck/initial-state)))))

(deftest decide-refuses-test
  (testing "no stock: nothing happened, and the caller is told why"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                          (truck/decide (buy "vanilla") {"vanilla" 0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                          (truck/decide (buy "pistachio") truck/initial-state)))))

(deftest a-refusal-carries-its-reason-test
  (is (= {:command/type :buy-flavour :flavour "pistachio" :remaining 0}
         (try (truck/decide (buy "pistachio") truck/initial-state)
              (catch clojure.lang.ExceptionInfo e (ex-data e))))))

(deftest zero-events-is-not-the-same-as-a-refusal-test
  (testing "both put nothing in the log; only one of them means success"
    (is (= [] (truck/decide (load-truck "vanilla" 0) truck/initial-state)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (truck/decide (buy "vanilla") truck/initial-state)))))

(deftest decide-is-a-function-of-command-and-state-test
  (testing "same inputs, same events, every time"
    (is (= (truck/decide (buy "vanilla") {"vanilla" 3})
           (truck/decide (buy "vanilla") {"vanilla" 3}))))
  (testing "and the state is what changes the answer"
    (is (not= (truck/decide (buy "vanilla") {"vanilla" 3})
              (truck/decide (buy "vanilla") {"vanilla" 1})))))

(deftest decide-returns-what-happened-not-where-it-goes-test
  (testing "proposals have no identity, stream, or version yet"
    (doseq [event (truck/decide (buy "vanilla") {"vanilla" 1})]
      (is (= #{:event/type :data} (set (keys event)))))))

(deftest decided-events-fold-back-into-state-test
  (testing "the output of decide is the input of evolve"
    (let [state  {"vanilla" 3}
          events (truck/decide (buy "vanilla") state)]
      (is (= {"vanilla" 2} (reduce truck/evolve state events))))))

(deftest known-events-irrelevant-to-stock-are-explicit-no-ops-test
  (is (= {"vanilla" 0}
         (truck/evolve {"vanilla" 0}
                       {:event/type :stock-depleted
                        :data       {:flavour "vanilla"}}))))

(deftest unknown-event-semantics-are-rejected-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/evolve truck/initial-state
                                      {:event/type :freezer-failed
                                       :data       {}}))))
