(ns lab7.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab7.stream :as s]))

(deftest every-event-names-its-history-test
  (doseq [event s/log]
    (is (uuid? (:stream/id event)) "whose history")
    (is (pos-int? (:stream/version event)) "where in that history")))

(deftest stream-id-separates-the-histories-test
  (testing "one truck's history is only that truck's events"
    (is (= 2 (count (s/stream s/log s/truck-1))))
    (is (= 2 (count (s/stream s/log s/truck-2))))
    (is (= (count s/log)
           (+ (count (s/stream s/log s/truck-1))
              (count (s/stream s/log s/truck-2)))))))

(deftest each-truck-folds-to-its-own-stock-test
  (is (= {"vanilla" 0} (s/state-of s/log s/truck-1)))
  (is (= {"vanilla" 2} (s/state-of s/log s/truck-2))))

(deftest folding-the-whole-log-answers-a-different-question-test
  (testing "four cones loaded, two sold, across the fleet"
    (is (= {"vanilla" 2} (s/replay s/log))))
  (testing "which is nobody's stock: truck 1 is empty, and the fleet total says otherwise"
    (is (= 0 (get (s/state-of s/log s/truck-1) "vanilla")))
    (is (pos? (get (s/replay s/log) "vanilla")))))

(deftest versions-are-contiguous-within-a-stream-test
  (testing "each truck's history is numbered 1..n with no gaps"
    (doseq [truck [s/truck-1 s/truck-2]]
      (let [versions (map :stream/version (s/stream s/log truck))]
        (is (= (range 1 (inc (count versions))) versions))))))

(deftest versions-are-not-unique-across-the-log-test
  (testing "version 1 exists once per truck — it only means anything beside a stream id"
    (let [firsts (filter #(= 1 (:stream/version %)) s/log)]
      (is (= 2 (count firsts)))
      (is (= 2 (count (distinct (map :stream/id firsts))))))))

(deftest current-version-test
  (is (= 2 (s/current-version s/log s/truck-1)))
  (is (= 2 (s/current-version s/log s/truck-2)))
  (testing "a stream that has never been written to is at version 0"
    (is (= 0 (s/current-version s/log (random-uuid))))))

(deftest append-continues-the-right-history-test
  (let [restock  {:event/id   (random-uuid)
                  :event/type :truck-loaded
                  :data       {:flavour "vanilla" :quantity 5}}
        appended (last (s/append s/log s/truck-1 2 restock))]
    (is (= s/truck-1 (:stream/id appended)))
    (is (= 3 (:stream/version appended)))
    (testing "and only that history changes"
      (is (= {"vanilla" 5} (s/state-of (s/append s/log s/truck-1 2 restock) s/truck-1)))
      (is (= {"vanilla" 2} (s/state-of (s/append s/log s/truck-1 2 restock) s/truck-2))))))

(deftest append-rejects-a-stale-expected-version-test
  (testing "two tills both read version 2; the second one loses"
    (let [sale   {:event/id   (random-uuid)
                  :event/type :flavour-sold
                  :data       {:flavour "vanilla"}}
          winner (s/append s/log s/truck-2 2 sale)]
      (is (= 3 (s/current-version winner s/truck-2)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Concurrent modification"
                            (s/append winner s/truck-2 2 sale))))))

(deftest the-conflict-carries-what-the-writer-needs-to-retry-test
  (let [sale   {:event/id   (random-uuid)
                :event/type :flavour-sold
                :data       {:flavour "vanilla"}}
        winner (s/append s/log s/truck-2 2 sale)
        data   (try (s/append winner s/truck-2 2 sale)
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:stream/id        s/truck-2
            :expected-version 2
            :actual-version   3}
           data))))

(deftest a-new-stream-starts-at-version-1-test
  (let [truck-3 (random-uuid)
        loaded  {:event/id   (random-uuid)
                 :event/type :truck-loaded
                 :data       {:flavour "pistachio" :quantity 4}}
        log     (s/append s/log truck-3 0 loaded)]
    (is (= 1 (s/current-version log truck-3)))
    (is (= {"pistachio" 4} (s/state-of log truck-3)))))
