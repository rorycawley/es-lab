(ns lab6.evolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab6.evolve :as e]))

(deftest an-empty-history-is-the-initial-state-test
  (is (= e/initial-state (e/replay []))))

(deftest state-is-derived-from-the-history-test
  (testing "three vanilla loaded and two sold, one chocolate loaded and sold"
    (is (= {:stock     {:vanilla 1 :chocolate 0}
            :last-sold :chocolate}
           (e/replay e/full-day)))))

(deftest replay-is-deterministic-test
  (testing "the same history always folds to the same state"
    (is (= (e/replay e/full-day) (e/replay e/full-day)))))

(deftest nothing-is-lost-by-discarding-state-test
  (testing "state can be thrown away and rebuilt; the events are the truth"
    (is (= (e/replay e/full-day)
           (reduce e/evolve e/initial-state e/full-day)))))

(deftest evolve-applies-one-event-at-a-time-test
  (testing "reduce is the only thing turning one-at-a-time into a history"
    (is (= {:stock {:vanilla 3} :last-sold nil}
           (e/evolve e/initial-state (e/truck-loaded :vanilla 3))))
    (is (= {:stock {:vanilla 2} :last-sold :vanilla}
           (-> e/initial-state
               (e/evolve (e/truck-loaded :vanilla 3))
               (e/evolve (e/flavour-sold :vanilla)))))))

(deftest counting-happens-to-commute-test
  (testing "adding and subtracting in any order lands on the same number"
    (is (= (:stock (e/replay e/full-day))
           (:stock (e/replay (reverse e/full-day)))))))

(deftest but-the-fold-as-a-whole-does-not-test
  (testing "reversed, the day ends on a different sale"
    (is (= :chocolate (:last-sold (e/replay e/full-day))))
    (is (= :vanilla (:last-sold (e/replay (reverse e/full-day))))))
  (testing "so the two histories are different states"
    (is (not= (e/replay e/full-day)
              (e/replay (reverse e/full-day))))))

(deftest evolve-never-says-no-test
  (testing "selling a flavour that was never loaded is nonsense, and applied anyway"
    (is (= {:stock {:pistachio -1} :last-sold :pistachio}
           (e/replay [(e/flavour-sold :pistachio)])))
    (testing "keeping the impossible out is decide's job, not evolve's"
      (is (neg? (get-in (e/replay [(e/flavour-sold :pistachio)])
                        [:stock :pistachio]))))))

(deftest the-fold-ignores-what-it-has-no-opinion-about-test
  (testing "stock-depleted is derivable from the count, so the fold skips it"
    (is (= (e/replay e/full-day)
           (e/replay (remove #(= :stock-depleted (:event/type %)) e/full-day)))))
  (testing "and an event type this namespace has never seen leaves state alone"
    (is (= {:stock {:vanilla 3} :last-sold nil}
           (e/replay [(e/truck-loaded :vanilla 3)
                      {:event/id   (random-uuid)
                       :event/type :truck-repainted
                       :data       {:colour :pink}}])))))

(deftest folding-is-resumable-test
  (testing "a partial fold can be carried forward instead of replayed from zero"
    (is (= (e/replay e/full-day)
           (reduce e/evolve (e/replay e/morning) e/afternoon)))))
