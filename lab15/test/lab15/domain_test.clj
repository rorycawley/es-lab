(ns lab15.domain-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab15.domain :as domain]))

(defn- command
  [type data]
  {:command/type type :data data})

(deftest card-behavior-is-pure-and-enforces-its-lifecycle-test
  (let [issue (command :issue-card
                       {:customer-id "C-123"
                        :personal    {:name "Aoife"}})
        proposal (first (domain/decide-card issue domain/initial-card))
        active   (domain/replay-card [proposal])]
    (is (= {:name "Aoife"} (get-in proposal [:data :personal]))
        "the domain proposal contains no cipher or key")
    (is (= :active (:status active)))
    (is (= :card-cancelled
           (:event/type
            (first (domain/decide-card (command :cancel-card {}) active)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Card already issued"
                          (domain/decide-card issue active)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Card is not active"
                          (domain/decide-card (command :cancel-card {})
                                              domain/initial-card)))))

(deftest truck-behavior-enforces-stock-and-positive-movements-test
  (let [loaded-event (first (domain/decide-truck
                             (command :load-truck
                                      {:flavour "vanilla" :quantity 2})
                             domain/initial-truck))
        loaded-state (domain/replay-truck [loaded-event])
        sale         (first (domain/decide-truck
                             (command :buy-flavour
                                      {:flavour "vanilla" :customer-id "C-123"})
                             loaded-state))]
    (is (= {"vanilla" 2} loaded-state))
    (is (= {:flavour "vanilla" :customer-id "C-123"} (:data sale)))
    (is (= {"vanilla" 1} (domain/replay-truck [loaded-event sale])))
    (testing "invalid quantities cannot create impossible stock"
      (doseq [quantity [0 -1 1.5 nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quantity must be"
                              (domain/decide-truck
                               (command :load-truck
                                        {:flavour "vanilla" :quantity quantity})
                               domain/initial-truck)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                          (domain/decide-truck
                           (command :buy-flavour
                                    {:flavour "vanilla" :customer-id "C-123"})
                           domain/initial-truck)))))

(deftest unknown-commands-do-not-become-no-ops-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown card command type"
                        (domain/decide-card (command :merge-cards {})
                                            domain/initial-card)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown truck command type"
                        (domain/decide-truck (command :teleport-stock {})
                                             domain/initial-truck))))
