(ns lab11.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab11.process :as process]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def conversation #uuid "cc79c083-0000-4000-8000-000000000001")

(def t0 #inst "2026-08-16T09:00:00.000-00:00")
(def within #inst "2026-08-16T09:20:00.000-00:00")
(def beyond #inst "2026-08-16T09:31:00.000-00:00")

(defn- depleted []
  {:event/type        :stock-depleted
   :event/occurred-at t0
   :stream/id         truck-1
   :data              {:flavour "vanilla"}})

(defn- unloaded []
  {:event/type :flavour-unloaded
   :stream/id  truck-2
   :data       {:flavour "vanilla" :quantity process/transfer-quantity}})

(defn- loaded []
  {:event/type :truck-loaded
   :stream/id  truck-1
   :data       {:flavour "vanilla" :quantity process/transfer-quantity}})

(defn- abandoned []
  {:event/type :transfer-abandoned
   :stream/id  truck-1
   :data       {:flavour "vanilla" :reason "donor-did-not-respond"}})

(deftest a-process-with-no-history-has-not-started-test
  (is (= {:status :not-started} (process/replay [])))
  (is (= [] (process/decide (process/replay []) conversation truck-2 t0))))

(deftest the-fold-tracks-which-step-the-process-is-on-test
  (is (= :awaiting-unload (:status (process/replay [(depleted)]))))
  (is (= :awaiting-load (:status (process/replay [(depleted) (unloaded)]))))
  (is (= :complete (:status (process/replay [(depleted) (unloaded) (loaded)]))))
  (is (= :abandoned (:status (process/replay [(depleted) (abandoned)])))))

(deftest the-fold-spans-two-trucks-test
  (testing "the unload happened on truck 2, the depletion on truck 1"
    (let [events [(depleted) (unloaded)]]
      (is (= 2 (count (distinct (map :stream/id events))))
          "two streams")
      (is (= :awaiting-load (:status (process/replay events)))
          "one process"))))

(deftest each-step-asks-for-the-next-thing-test
  (let [ask (fn [events now]
              (process/decide (process/replay events) conversation truck-2 now))]
    (testing "depleted → ask the donor to give some up"
      (let [[c] (ask [(depleted)] within)]
        (is (= :unload-flavour (:command/type c)))
        (is (= truck-2 (get-in c [:data :truck-id])))))
    (testing "unloaded → ask the empty truck to take it"
      (let [[c] (ask [(depleted) (unloaded)] within)]
        (is (= :load-truck (:command/type c)))
        (is (= truck-1 (get-in c [:data :truck-id])))))
    (testing "loaded → nothing left to ask"
      (is (= [] (ask [(depleted) (unloaded) (loaded)] within))))
    (testing "abandoned → nothing left to ask"
      (is (= [] (ask [(depleted) (abandoned)] within))))))

(deftest waiting-too-long-abandons-the-transfer-test
  (let [state (process/replay [(depleted)])]
    (testing "still within the timeout, it keeps asking"
      (is (= :unload-flavour
             (:command/type (first (process/decide state conversation truck-2 within))))))
    (testing "past it, it gives up"
      (let [[c] (process/decide state conversation truck-2 beyond)]
        (is (= :abandon-transfer (:command/type c)))
        (is (= "donor-did-not-respond" (get-in c [:data :reason])))))))

(deftest time-is-the-only-thing-that-changed-test
  (testing "same state, same events, different answer — which is the point"
    (let [state (process/replay [(depleted)])]
      (is (not= (process/decide state conversation truck-2 within)
                (process/decide state conversation truck-2 beyond))))))

(deftest command-ids-are-derived-and-do-not-collide-across-steps-test
  (testing "stable for one step of one conversation"
    (is (= (process/derived-command-id conversation :unload)
           (process/derived-command-id conversation :unload))))
  (testing "different steps of the same conversation differ"
    (is (= 3 (count (distinct (map #(process/derived-command-id conversation %)
                                   [:unload :load :abandon]))))))
  (testing "and the same step of different conversations differs"
    (is (not= (process/derived-command-id conversation :unload)
              (process/derived-command-id (random-uuid) :unload)))))

(deftest every-command-carries-the-conversation-test
  (doseq [events [[(depleted)] [(depleted) (unloaded)]]]
    (doseq [c (process/decide (process/replay events) conversation truck-2 within)]
      (is (= conversation (:correlation-id c))))))
