(ns lab19.visibility-test
  "The trap labs 9 and 12 and REFERENCE.md all warn about, finally shown.

  In every earlier lab the log was a vector, so a position *was* an index and
  the ordering was exact. A real store assigns positions at INSERT and makes
  rows visible at COMMIT, and those are different moments."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [lab19.fixture :as fixture]
            [lab19.store :as store]
            [next.jdbc :as jdbc]))

(use-fixtures :each fixture/with-store)

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- insert!
  "One event, on a connection whose transaction we control."
  [conn version]
  (jdbc/execute-one!
   conn
   ["INSERT INTO event (event_id, event_type, stream_id, stream_version,
                        occurred_at, data, metadata)
     VALUES (?,?,?,?,?, '{}'::jsonb, '{}'::jsonb) RETURNING global_position"
    (gen-id) "flavour-sold" truck-1 version (java.sql.Timestamp. (.getTime t0))]))

(defn- positions [events] (mapv :event/position events))

(defn- with-two-open-transactions
  "Two connections with autocommit off, so commit order is ours to choose.

  That control is the whole apparatus: the gap only opens when one transaction
  takes a position before another and commits after it."
  [ds f]
  (let [a (jdbc/get-connection ds)
        b (jdbc/get-connection ds)]
    (.setAutoCommit a false)
    (.setAutoCommit b false)
    (try (f a b)
         (finally (.close a) (.close b)))))

(deftest a-naive-reader-steps-over-the-gap-and-never-returns-test
  (let [ds (fixture/datasource)]
    (with-two-open-transactions
      ds
      (fn [a b]
        ;; A takes position 1 and holds its transaction open.
        (is (= 1 (:event/global_position (insert! a 1))))
        ;; B takes position 2 and commits first.
        (is (= 2 (:event/global_position (insert! b 2))))
        (.commit b)

        (testing "the reader can only see B, so it checkpoints at 2"
          (is (= [2] (positions (store/since ds 0)))))

        (.commit a)

        (testing "A commits — and position 1 is now behind the checkpoint"
          (is (= [] (positions (store/since ds 2)))
              "silently skipped, forever, with nothing to report it"))

        (testing "the event is right there; the reader simply passed it"
          (is (= [1 2] (positions (store/since ds 0)))))))))

(deftest holding-back-in-flight-transactions-closes-it-test
  (let [ds (fixture/datasource)]
    (with-two-open-transactions
      ds
      (fn [a b]
        (insert! a 1)
        (insert! b 2)
        (.commit b)

        (testing "B is committed, but A started earlier and is still open"
          (is (= [2] (positions (store/since ds 0))) "the naive read sees it")
          (is (= [] (positions (store/since-committed ds 0)))
              "the safe read holds it back — nothing is settled yet"))

        (.commit a)

        (testing "once A is done, both events arrive together and in order"
          (is (= [1 2] (positions (store/since-committed ds 0)))))))))

(deftest the-safe-read-costs-latency-not-correctness-test
  (let [ds (fixture/datasource)]
    (with-two-open-transactions
      ds
      (fn [a b]
        (insert! a 1)
        (insert! b 2)
        (.commit b)
        (testing "an event that is committed and readable is still withheld"
          (is (some #(= 2 (:event/position %)) (store/since ds 0)))
          (is (empty? (store/since-committed ds 0))))
        (testing "the trade: a reader lags the writer by the longest open transaction"
          (is (< (count (store/since-committed ds 0))
                 (count (store/since ds 0)))))
        (.commit a)))))

(deftest with-no-concurrency-the-two-agree-test
  (testing "the gap needs an overlapping transaction to open at all"
    (let [ds (fixture/datasource)]
      (store/append ds truck-1 0 gen-id t0 {:command/id (random-uuid)}
                    [{:event/type :flavour-sold :data {:flavour "vanilla"}}])
      (is (= (positions (store/since ds 0))
             (positions (store/since-committed ds 0))
             [1])))))

(deftest a-checkpoint-is-only-as-safe-as-the-read-that-advanced-it-test
  (testing "the whole point: what a projection (lab 9) or relay (lab 12) loses"
    (let [ds (fixture/datasource)]
      (with-two-open-transactions
        ds
        (fn [a b]
          (insert! a 1)
          (insert! b 2)
          (.commit b)
          (let [naive (->> (store/since ds 0) (map :event/position) (apply max 0))
                safe  (->> (store/since-committed ds 0) (map :event/position) (apply max 0))]
            (.commit a)
            (testing "the naive projection has already moved past the missing event"
              (is (= 2 naive))
              (is (empty? (store/since ds naive))))
            (testing "the safe one never moved, so it still gets both"
              (is (= 0 safe))
              (is (= [1 2] (positions (store/since-committed ds safe)))))))))))
