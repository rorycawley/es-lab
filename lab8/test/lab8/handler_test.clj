(ns lab8.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab8.handler :as handler]
            [lab8.store :as store]
            [lab8.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(defn- gen-id [] (random-uuid))

(defn- buy [flavour] {:command/type :buy-flavour :data {:flavour flavour}})
(defn- load-truck [flavour quantity]
  {:command/type :load-truck :data {:flavour flavour :quantity quantity}})

(defn- state-of [log stream-id]
  (truck/replay (store/stream log stream-id)))

(def loaded
  (handler/handle [] gen-id truck-1 (load-truck "vanilla" 3)))

(deftest the-loop-turns-a-command-into-history-test
  (is (= 1 (count loaded)))
  (is (= {"vanilla" 3} (state-of loaded truck-1))))

(deftest the-store-stamps-what-the-domain-left-out-test
  (let [event (first loaded)]
    (is (uuid? (:event/id event)))
    (is (= truck-1 (:stream/id event)))
    (is (= 1 (:stream/version event)))))

(deftest a-sale-advances-the-stream-test
  (let [log (handler/handle loaded gen-id truck-1 (buy "vanilla"))]
    (is (= {"vanilla" 2} (state-of log truck-1)))
    (is (= 2 (store/current-version log truck-1)))))

(deftest many-events-land-together-with-consecutive-versions-test
  (let [log (-> (handler/handle [] gen-id truck-1 (load-truck "vanilla" 1))
                (handler/handle gen-id truck-1 (buy "vanilla")))]
    (is (= [:truck-loaded :flavour-sold :stock-depleted]
           (map :event/type (store/stream log truck-1))))
    (is (= [1 2 3] (map :stream/version (store/stream log truck-1))))))

(deftest a-refusal-writes-nothing-test
  (let [log (handler/handle [] gen-id truck-1 (load-truck "vanilla" 1))
        sold (handler/handle log gen-id truck-1 (buy "vanilla"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                          (handler/handle sold gen-id truck-1 (buy "vanilla"))))
    (testing "the log the caller already holds is untouched — it is a value"
      (is (= 3 (count sold))))))

(deftest zero-events-leaves-the-log-alone-test
  (let [log (handler/handle loaded gen-id truck-1 (load-truck "vanilla" 0))]
    (is (= loaded log))
    (is (= 1 (store/current-version log truck-1)))))

(deftest each-truck-decides-against-its-own-state-test
  (let [log (-> (handler/handle [] gen-id truck-1 (load-truck "vanilla" 1))
                (handler/handle gen-id truck-2 (load-truck "vanilla" 5))
                (handler/handle gen-id truck-1 (buy "vanilla")))]
    (is (= {"vanilla" 0} (state-of log truck-1)))
    (is (= {"vanilla" 5} (state-of log truck-2)))
    (testing "truck 1 is empty even though the fleet is not"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                            (handler/handle log gen-id truck-1 (buy "vanilla"))))
      (is (= (inc (count log))
             (count (handler/handle log gen-id truck-2 (buy "vanilla"))))))))

(deftest a-stale-decision-is-refused-at-the-append-test
  (testing "two tills read the same log, both decide, the second one loses"
    (let [log    (handler/handle [] gen-id truck-1 (load-truck "vanilla" 2))
          till-a (handler/handle log gen-id truck-1 (buy "vanilla"))]
      (is (= 2 (store/current-version till-a truck-1)))
      (testing "till B decided against `log`, which has since moved on"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Concurrent modification"
                              (store/append till-a truck-1 1 gen-id
                                            (truck/decide (buy "vanilla")
                                                          (state-of log truck-1)))))))))

(deftest retrying-against-fresh-state-succeeds-test
  (testing "the losing till reads again, folds again, decides again"
    (let [log    (handler/handle [] gen-id truck-1 (load-truck "vanilla" 2))
          till-a (handler/handle log gen-id truck-1 (buy "vanilla"))
          till-b (handler/handle till-a gen-id truck-1 (buy "vanilla"))]
      (is (= {"vanilla" 0} (state-of till-b truck-1)))
      (testing "and the second sale correctly emitted the depletion"
        (is (= [:truck-loaded :flavour-sold :flavour-sold :stock-depleted]
               (map :event/type (store/stream till-b truck-1))))))))
