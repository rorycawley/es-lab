(ns lab2.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab2.command :as command]))

(deftest buy-flavour-shape-test
  (testing "each example has the buy-flavour command type"
    (doseq [example command/examples]
      (is (= :buy-flavour (:command/type example))))))

(deftest buy-flavour-data-test
  (testing "each example names a flavour in its data"
    (doseq [example command/examples]
      (is (keyword? (get-in example [:data :flavour]))))))

(deftest buy-flavour-vanilla-command-test
  (is (= {:command/type :buy-flavour
          :data         {:flavour :vanilla}}
         command/buy-flavour-vanilla-command)))

(deftest examples-are-distinct-test
  (is (= (count command/examples)
         (count (distinct command/examples)))))

(deftest the-pair-is-named-for-two-different-moments-test
  (testing "the command asks; the event reports"
    (is (= :buy-flavour (:command/type command/buy-flavour-vanilla-command)))
    (is (= :flavour-sold (:event/type command/flavour-sold-vanilla-event))))
  (testing "and neither map carries the other's naming key"
    (is (nil? (:event/type command/buy-flavour-vanilla-command)))
    (is (nil? (:command/type command/flavour-sold-vanilla-event)))))

(deftest command-and-event-are-symmetrical-test
  (testing "same data, same frame — only the key naming the shape differs"
    (let [c command/buy-flavour-vanilla-command
          e command/flavour-sold-vanilla-event]
      (is (= (:data c) (:data e)))
      (is (= #{:command/type :data} (set (keys c))))
      (is (= #{:event/type :data} (set (keys e))))))
  (testing "so the shape alone cannot tell you which one you are holding"
    (let [strip #(dissoc % :command/type :event/type)]
      (is (= (strip command/buy-flavour-vanilla-command)
             (strip command/flavour-sold-vanilla-event))))))

(deftest a-state-changing-command-names-its-target-test
  (testing "commands are routed, so something must say where"
    (is (uuid? (get-in command/buy-flavour-addressed [:data :truck-id]))))
  (testing "a receiver-minted address would be new on every arrival"
    (let [minted-on-arrival #(assoc-in command/buy-flavour-addressed
                                       [:data :truck-id] (random-uuid))]
      (is (not= (get-in (minted-on-arrival) [:data :truck-id])
                (get-in (minted-on-arrival) [:data :truck-id]))
          "so a retry could never be recognised as addressing the same truck"))))

(deftest a-command-carries-only-what-the-behaviour-needs-test
  (let [needed (set (keys (:data command/buy-flavour-addressed)))
        sent   (set (keys (:data command/buy-flavour-carrying-the-whole-truck)))]
    (is (= #{:truck-id :flavour} needed))
    (testing "the entity-carrying version ships state the handler will re-read anyway"
      (is (contains? sent :truck))
      (is (some? (get-in command/buy-flavour-carrying-the-whole-truck
                         [:data :truck :stock])))
      (testing "and that copy can already be wrong by the time it arrives"
        (is (not= (get-in command/buy-flavour-carrying-the-whole-truck
                          [:data :truck :stock])
                  {:vanilla 0 :chocolate 0}))))))
