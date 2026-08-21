(ns lab11.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab11.process :as process]
            [lab11.runner :as runner]
            [lab11.store :as store]
            [lab11.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(def t0     #inst "2026-08-16T09:00:00.000-00:00")
(def within #inst "2026-08-16T09:20:00.000-00:00")
(def beyond #inst "2026-08-16T09:31:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type conversation data]
  {:command/id     (random-uuid)
   :command/type   type
   :correlation-id conversation
   :data           data})

(defn- stock-of [log stream-id]
  (truck/replay (store/stream log stream-id)))

(defn- conversation-of [log] (get-in (last log) [:metadata :correlation-id]))

(defn- trading-day
  "Truck 1 loads one cone and sells it, emptying itself. Truck 2 is the donor,
  stocked with `donor-stock`. Built by issuing commands — nothing here rewrites
  an event, because nothing can."
  [donor-stock]
  (let [setup (random-uuid)
        sale  (random-uuid)]
    (-> []
        (runner/handle gen-id t0 (command :load-truck setup
                                          {:truck-id truck-1 :flavour "vanilla" :quantity 1}))
        (runner/handle gen-id t0 (command :load-truck setup
                                          {:truck-id truck-2 :flavour "vanilla" :quantity donor-stock}))
        (runner/handle gen-id t0 (command :buy-flavour sale
                                          {:truck-id truck-1 :flavour "vanilla"})))))

(def sold-out (trading-day 50))
(def conversation (conversation-of sold-out))

;; The same day, except the donor has one cone and cannot spare ten.
(def poor-donor (trading-day 1))

(deftest the-setup-leaves-one-truck-empty-and-one-stocked-test
  (is (= {"vanilla" 0} (stock-of sold-out truck-1)))
  (is (= {"vanilla" 50} (stock-of sold-out truck-2)))
  (is (= :stock-depleted (:event/type (last sold-out)))))

(deftest the-process-runs-to-completion-test
  (testing "two passes: ask the donor, then load the empty truck"
    (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
      (is (= {"vanilla" process/transfer-quantity} (stock-of log truck-1)))
      (is (= {"vanilla" (- 50 process/transfer-quantity)} (stock-of log truck-2)))
      (is (= :complete (:status (process/replay (store/correlated log conversation))))))))

(deftest the-conversation-spans-both-trucks-test
  (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)
        history (store/correlated log conversation)]
    (is (= [:flavour-sold :stock-depleted :flavour-unloaded :truck-loaded]
           (map :event/type history)))
    (testing "which no single stream could tell you"
      (is (= 2 (count (distinct (map :stream/id history))))))))

(deftest the-donor-refusing-produces-silence-test
  (testing "truck 2 cannot spare 10, so nothing at all is recorded"
    (let [before        (count poor-donor)
          {:keys [log]} (runner/run-once poor-donor 0 gen-id within truck-2)]
      (is (= 1 (get (stock-of poor-donor truck-2) "vanilla")) "donor has one cone")
      (is (= before (count log)) "the refusal left no trace")
      (is (= :awaiting-unload
             (:status (process/replay (store/correlated log (conversation-of poor-donor)))))
          "so the process is still waiting"))))

(deftest silence-is-what-the-timeout-is-for-test
  (let [cid           (conversation-of poor-donor)
        {:keys [log]} (runner/run-once poor-donor 0 gen-id beyond truck-2)]
    (is (= :transfer-abandoned (:event/type (last log))))
    (is (= :abandoned (:status (process/replay (store/correlated log cid)))))))

(deftest an-abandoned-process-stays-abandoned-test
  (testing "the give-up is recorded as a fact, so it does not fire again"
    (let [once  (:log (runner/run-once poor-donor 0 gen-id beyond truck-2))
          twice (:log (runner/run-once once 0 gen-id beyond truck-2))]
      (is (= (count once) (count twice))))))

(deftest re-reading-the-same-batch-does-not-repeat-a-step-test
  (testing "derived command ids make the pass idempotent (lab 10)"
    (let [once  (:log (runner/run-once sold-out 0 gen-id within truck-2))
          twice (:log (runner/run-once once 0 gen-id within truck-2))
          thrice (:log (runner/run-once twice 0 gen-id within truck-2))]
      (is (= {"vanilla" process/transfer-quantity} (stock-of thrice truck-1))
          "loaded once, not three times"))))

(deftest every-event-records-both-ids-test
  (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
    (doseq [event (store/correlated log conversation)]
      (is (uuid? (get-in event [:metadata :causation-id])) "what caused this")
      (is (= conversation (get-in event [:metadata :correlation-id])) "what it is part of"))))

(deftest the-process-manager-never-writes-an-event-itself-test
  (testing "every fact in the log was decided by the truck aggregate"
    (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
      (doseq [event log]
        (is (contains? #{truck-1 truck-2} (:stream/id event))
            "no process manager stream exists")))))

(deftest nothing-new-means-nothing-happens-test
  (let [{:keys [log checkpoint]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)
        quiet (runner/run-once log checkpoint gen-id within truck-2)]
    (is (= log (:log quiet)))))
