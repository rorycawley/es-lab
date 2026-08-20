(ns lab9.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab9.store :as store]))

(defn- gen-id [] (random-uuid))

(deftest position-orders-the-whole-log-test
  (testing "unique and contiguous across every stream"
    (is (= (range 1 (inc (count store/log)))
           (map :event/position store/log)))))

(deftest version-orders-only-one-stream-test
  (testing "contiguous once you filter to a truck, and not before"
    (doseq [truck [store/truck-1 store/truck-2]]
      (let [versions (map :stream/version (store/stream store/log truck))]
        (is (= (range 1 (inc (count versions))) versions)))))
  (testing "and the same version number exists on both trucks"
    (is (= 2 (count (filter #(= 1 (:stream/version %)) store/log))))))

(deftest since-returns-what-came-after-test
  (is (= [4 5 6] (map :event/position (store/since store/log 3))))
  (is (= (count store/log) (count (store/since store/log 0))))
  (is (= [] (store/since store/log 6))))

(deftest since-spans-streams-test
  (testing "the resume point is global, so it picks up both trucks"
    (is (= 2 (count (distinct (map :stream/id (store/since store/log 3))))))))

(deftest append-continues-both-numberings-test
  (let [sale {:event/type :flavour-sold :data {:flavour :chocolate}}
        log  (store/append store/log store/truck-2 3 gen-id [sale])
        last-event (last log)]
    (is (= 7 (:event/position last-event)) "next in the whole log")
    (is (= 4 (:stream/version last-event)) "next in that truck's history")))
