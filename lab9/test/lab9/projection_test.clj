(ns lab9.projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab9.projection :as p]
            [lab9.store :as store]))

(def restock-id #uuid "018f7a3e-0000-7000-8000-000000000911")
(def chocolate-sale-id #uuid "018f7a3e-0000-7000-8000-000000000912")
(def vanilla-sale-id #uuid "018f7a3e-0000-7000-8000-000000000913")
(def unknown-event-id #uuid "018f7a3e-0000-7000-8000-000000000914")

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

(deftest a-projection-explicitly-ignores-known-irrelevant-events-test
  (testing "popularity counts sales, so restocking leaves it unchanged"
    (let [restock {:event/id   restock-id
                   :event/type :truck-loaded
                   :data       {:flavour "vanilla" :quantity 9}}
          log     (store/append store/log store/truck-1 3 [restock])]
      (is (= (:state (p/rebuild p/popularity store/log))
             (:state (p/rebuild p/popularity log)))))))

(deftest projections-reject-unknown-event-semantics-test
  (let [event {:event/id   unknown-event-id
               :event/type :freezer-failed
               :data       {}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (p/popularity {} event)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (p/fleet-stock {} event)))))

(deftest the-checkpoint-tracks-what-has-been-consumed-test
  (let [model (p/rebuild p/popularity store/log)]
    (is (= 6 (:checkpoint model)))
    (is (= 0 (:checkpoint (p/empty-model {}))))
    (is (= #{:state :checkpoint} (set (keys model)))
        "runtime projection code is not persisted inside the model")))

(deftest checkpoint-is-a-position-not-an-event-count-test
  (let [gapped-log (remove #(= 4 (:event/position %)) store/log)
        model      (p/rebuild p/popularity gapped-log)]
    (is (= {"vanilla" 2 "chocolate" 1} (:state model)))
    (is (= 6 (:checkpoint model))
        "five consumed events can legitimately end at position six")))

(deftest advancing-with-no-new-events-changes-nothing-test
  (testing "which is what lets a projection poll"
    (let [model (p/rebuild p/popularity store/log)]
      (is (= model (p/advance model p/popularity store/log)))
      (is (= model (-> model
                       (p/advance p/popularity store/log)
                       (p/advance p/popularity store/log)))))))

(deftest advancing-folds-only-the-new-events-test
  (let [model (p/rebuild p/popularity store/log)
        sale  {:event/id   chocolate-sale-id
               :event/type :flavour-sold
               :data       {:flavour "chocolate"}}
        log   (store/append store/log store/truck-2 3 [sale])
        moved (p/advance model p/popularity log)]
    (is (= {"vanilla" 2 "chocolate" 3} (:state moved)))
    (is (= 7 (:checkpoint moved)))))

(deftest incremental-equals-rebuilt-test
  (testing "catching up event by event lands where a full replay does"
    (let [sale {:event/id   chocolate-sale-id
                :event/type :flavour-sold
                :data       {:flavour "chocolate"}}
          log  (store/append store/log store/truck-2 3 [sale])]
      (is (= (:state (p/rebuild p/popularity log))
             (:state (p/advance (p/rebuild p/popularity store/log)
                                p/popularity log)))))))

(deftest a-derived-read-model-can-be-thrown-away-test
  (testing "a model caught up incrementally is indistinguishable from a fresh rebuild"
    (let [sale  {:event/id   vanilla-sale-id
                 :event/type :flavour-sold
                 :data       {:flavour "vanilla"}}
          log   (store/append store/log store/truck-1 3 [sale])
          grown (p/advance (p/rebuild p/popularity store/log)
                           p/popularity log)]
      (is (= (p/rebuild p/popularity log) grown)))))

(deftest a-projection-invented-today-still-knows-all-of-history-test
  (testing "a brand new read model, written after the events were recorded"
    (let [busiest-truck
          (fn [model event]
            (case (:event/type event)
              :flavour-sold
              (update model (:stream/id event) (fnil inc 0))

              (:truck-loaded :stock-depleted)
              model

              (throw (ex-info "Unknown event type"
                              {:event/type (:event/type event)}))))]
      (is (= {store/truck-1 2 store/truck-2 2}
             (:state (p/rebuild busiest-truck store/log)))))))
