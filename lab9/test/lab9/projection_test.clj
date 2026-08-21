(ns lab9.projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab9.projection :as p]
            [lab9.store :as store]))

(defn- gen-id [] (random-uuid))

(deftest a-projection-answers-a-question-about-the-fleet-test
  (testing "two vanilla and two chocolate sold, across both trucks"
    (is (= {"vanilla" 2 "chocolate" 2}
           (:state (p/rebuild p/popularity store/log))))))

(deftest a-projection-can-still-report-per-truck-test
  (is (= {store/truck-1 {"vanilla" 0}
          store/truck-2 {"chocolate" 1}}
         (:state (p/rebuild p/fleet-stock store/log)))))

(deftest projections-are-independent-test
  (testing "two read models over the same log, neither aware of the other"
    (let [log store/log]
      (is (not= (:state (p/rebuild p/popularity log))
                (:state (p/rebuild p/fleet-stock log)))))))

(deftest a-projection-ignores-what-it-has-no-opinion-about-test
  (testing "popularity counts sales, so restocking leaves it unchanged"
    (let [restock {:event/type :truck-loaded :data {:flavour "vanilla" :quantity 9}}
          log     (store/append store/log store/truck-1 3 gen-id [restock])]
      (is (= (:state (p/rebuild p/popularity store/log))
             (:state (p/rebuild p/popularity log)))))))

(deftest the-checkpoint-tracks-what-has-been-consumed-test
  (let [model (p/rebuild p/popularity store/log)]
    (is (= 6 (:checkpoint model)))
    (is (= 0 (:checkpoint (p/empty-model p/popularity))))))

(deftest advancing-with-no-new-events-changes-nothing-test
  (testing "which is what lets a projection poll"
    (let [model (p/rebuild p/popularity store/log)]
      (is (= model (p/advance model store/log)))
      (is (= model (-> model
                       (p/advance store/log)
                       (p/advance store/log)))))))

(deftest advancing-folds-only-the-new-events-test
  (let [model (p/rebuild p/popularity store/log)
        sale  {:event/type :flavour-sold :data {:flavour "chocolate"}}
        log   (store/append store/log store/truck-2 3 gen-id [sale])
        moved (p/advance model log)]
    (is (= {"vanilla" 2 "chocolate" 3} (:state moved)))
    (is (= 7 (:checkpoint moved)))))

(deftest incremental-equals-rebuilt-test
  (testing "catching up event by event lands where a full replay does"
    (let [sale {:event/type :flavour-sold :data {:flavour "chocolate"}}
          log  (store/append store/log store/truck-2 3 gen-id [sale])]
      (is (= (:state (p/rebuild p/popularity log))
             (:state (p/advance (p/rebuild p/popularity store/log) log)))))))

(deftest a-read-model-can-always-be-thrown-away-test
  (testing "a model caught up incrementally is indistinguishable from a fresh rebuild"
    (let [sale  {:event/type :flavour-sold :data {:flavour "vanilla"}}
          log   (store/append store/log store/truck-1 3 gen-id [sale])
          grown (p/advance (p/rebuild p/popularity store/log) log)]
      (is (= (p/rebuild p/popularity log) grown)))))

(deftest a-projection-invented-today-still-knows-all-of-history-test
  (testing "a brand new read model, written after the events were recorded"
    (let [busiest-truck (fn [model event]
                          (if (= :flavour-sold (:event/type event))
                            (update model (:stream/id event) (fnil inc 0))
                            model))]
      (is (= {store/truck-1 2 store/truck-2 2}
             (:state (p/rebuild busiest-truck store/log)))))))
