(ns lab14.truck-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab14.truck :as truck]))

(def commissioned
  {:event/type :truck-commissioned :data {:capacity 20}})

(def loaded
  {:event/type :truck-loaded
   :data       {:flavour "vanilla" :quantity 10}})

(defn- command
  [type data]
  {:command/type type :data data})

(deftest capacity-is-an-aggregate-invariant-test
  (let [state (truck/replay [commissioned loaded])]
    (is (= [{:event/type :truck-loaded
             :data       {:flavour "chocolate" :quantity 10}}]
           (truck/decide (command :load-truck
                                  {:flavour "chocolate" :quantity 10})
                         state)))
    (is (= :load-refused
           (:event/type
            (first (truck/decide (command :load-truck
                                          {:flavour "chocolate" :quantity 11})
                                 state)))))))

(deftest compensation-is-a-domain-action-with-distinct-outcomes-test
  (let [has-room (truck/replay [commissioned])
        full     (truck/replay [commissioned
                                {:event/type :truck-loaded
                                 :data {:flavour "chocolate" :quantity 20}}])]
    (is (= :flavour-returned
           (:event/type
            (first (truck/decide (command :return-stock
                                          {:flavour "vanilla" :quantity 10})
                                 has-room)))))
    (is (= :stock-return-refused
           (:event/type
            (first (truck/decide (command :return-stock
                                          {:flavour "vanilla" :quantity 10})
                                 full)))))))

(deftest invalid-numbers-cannot-create-impossible-stock-test
  (testing "capacity must be a positive integer"
    (doseq [capacity [0 -1 1.5 nil]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Capacity must be"
                            (truck/decide (command :commission-truck
                                                   {:capacity capacity})
                                          truck/initial-state)))))
  (testing "every stock movement must use a positive integer quantity"
    (let [state (truck/replay [commissioned loaded])]
      (doseq [type [:load-truck :unload-flavour :return-stock]
              quantity [0 -1 1.5 nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quantity must be"
                              (truck/decide (command type
                                                     {:flavour "vanilla"
                                                      :quantity quantity})
                                            state)))))))

(deftest folds-ignore-only-known-state-neutral-facts-test
  (is (= (truck/replay [commissioned loaded])
         (truck/replay [commissioned loaded
                        {:event/type :load-refused}
                        {:event/type :stock-return-refused}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}]))))

(deftest unknown-commands-remain-visible-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                        (truck/decide (command :teleport-stock {})
                                      truck/initial-state))))
