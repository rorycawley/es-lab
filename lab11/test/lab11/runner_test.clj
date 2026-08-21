(ns lab11.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab11.process :as process]
            [lab11.runner :as runner]
            [lab11.store :as store]
            [lab11.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(def setup-conversation
  #uuid "cc79c083-0000-4000-8000-000000000001")

(def conversation
  #uuid "cc79c083-0000-4000-8000-000000000002")

(def load-empty-truck-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001101")

(def load-donor-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001102")

(def buy-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001103")

(def unknown-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001104")

(def first-process-event-id
  #uuid "018f7a3e-0000-7000-8000-000000002001")

(def t0     #inst "2026-08-16T09:00:00.000-00:00")
(def within #inst "2026-08-16T09:20:00.000-00:00")
(def beyond #inst "2026-08-16T09:31:00.000-00:00")

(defn- counting-ids
  "A deterministic fake for the application's identifier port."
  [start]
  (let [counter (atom start)]
    (fn []
      (java.util.UUID/fromString
       (format "018f7a3e-0000-7000-8000-%012d" (swap! counter inc))))))

(defn- command [command-id type correlation-id data]
  {:command/id     command-id
   :command/type   type
   :correlation-id correlation-id
   :data           data})

(defn- stock-of [log stream-id]
  (truck/replay (store/stream log stream-id)))

(defn- trading-day
  "Truck 1 sells its final cone. Truck 2 starts with `donor-stock`."
  [donor-stock]
  (let [gen-id (counting-ids 1000)]
    (-> []
        (runner/handle gen-id t0
                       (command load-empty-truck-command-id
                                :load-truck
                                setup-conversation
                                {:truck-id truck-1
                                 :flavour "vanilla"
                                 :quantity 1}))
        (runner/handle gen-id t0
                       (command load-donor-command-id
                                :load-truck
                                setup-conversation
                                {:truck-id truck-2
                                 :flavour "vanilla"
                                 :quantity donor-stock}))
        (runner/handle gen-id t0
                       (command buy-command-id
                                :buy-flavour
                                conversation
                                {:truck-id truck-1
                                 :flavour "vanilla"})))))

(deftest the-setup-leaves-one-truck-empty-and-one-stocked-test
  (let [sold-out (trading-day 50)]
    (is (= {"vanilla" 0} (stock-of sold-out truck-1)))
    (is (= {"vanilla" 50} (stock-of sold-out truck-2)))
    (is (= :stock-depleted (:event/type (last sold-out))))))

(deftest the-process-runs-to-completion-test
  (testing "two productive passes: unload the donor, then load the empty truck"
    (let [sold-out (trading-day 50)
          {:keys [log passes]}
          (runner/run-until-quiet sold-out 0 (counting-ids 2000) within truck-2 2)]
      (is (= 2 passes))
      (is (= {"vanilla" process/transfer-quantity} (stock-of log truck-1)))
      (is (= {"vanilla" (- 50 process/transfer-quantity)} (stock-of log truck-2)))
      (is (= :complete
             (:status (process/replay (store/correlated log conversation))))))))

(deftest the-conversation-spans-both-trucks-test
  (let [sold-out (trading-day 50)
        {:keys [log]}
        (runner/run-until-quiet sold-out 0 (counting-ids 2000) within truck-2)
        history (store/correlated log conversation)]
    (is (= [:flavour-sold :stock-depleted :flavour-unloaded :truck-loaded]
           (map :event/type history)))
    (testing "which no single aggregate stream could tell you"
      (is (= 2 (count (distinct (map :stream/id history))))))))

(deftest the-donor-refusing-produces-silence-test
  (testing "the expected business refusal records no event"
    (let [poor-donor (trading-day 1)
          before     (count poor-donor)
          {:keys [log checkpoint]}
          (runner/run-once poor-donor 0 (counting-ids 2000) within truck-2)]
      (is (= 1 (get (stock-of poor-donor truck-2) "vanilla")))
      (is (= before (count log)))
      (is (= (store/last-position poor-donor) checkpoint)
          "the triggering events were still checkpointed")
      (is (= :awaiting-unload
             (:status (process/replay (store/correlated log conversation))))))))

(deftest a-timer-wakes-a-process-after-its-event-checkpoint-advanced-test
  (let [poor-donor (trading-day 1)
        first-pass (runner/run-once poor-donor 0 (counting-ids 2000) within truck-2)
        checkpoint (:checkpoint first-pass)
        log        (:log first-pass)]
    (is (empty? (store/since log checkpoint))
        "there is no new event available to wake the process")
    (let [timed-out (runner/run-once log checkpoint (counting-ids 2000) beyond truck-2)]
      (is (= :transfer-abandoned (-> timed-out :log last :event/type)))
      (is (= :abandoned
             (:status (process/replay
                       (store/correlated (:log timed-out) conversation))))))))

(deftest an-abandoned-process-stays-abandoned-test
  (let [poor-donor (trading-day 1)
        first-pass (runner/run-once poor-donor 0 (counting-ids 2000) within truck-2)
        timed-out  (runner/run-once (:log first-pass)
                                    (:checkpoint first-pass)
                                    (counting-ids 2000)
                                    beyond
                                    truck-2)
        settled    (runner/run-until-quiet (:log timed-out)
                                           (:checkpoint timed-out)
                                           (counting-ids 3000)
                                           beyond
                                           truck-2)]
    (is (= (count (:log timed-out)) (count (:log settled))))))

(deftest re-reading-the-same-batch-does-not-repeat-a-step-test
  (testing "derived command ids make successful steps recognisable on redelivery"
    (let [sold-out (trading-day 50)
          once  (:log (runner/run-once sold-out 0 (counting-ids 2000) within truck-2))
          twice (:log (runner/run-once once 0 (counting-ids 3000) within truck-2))
          thrice (:log (runner/run-once twice 0 (counting-ids 4000) within truck-2))]
      (is (= {"vanilla" process/transfer-quantity} (stock-of thrice truck-1)))
      (is (= 6 (count thrice)) "four setup facts and two transfer facts"))))

(deftest events-preserve-application-identity-causation-and-correlation-test
  (let [sold-out (trading-day 50)
        {:keys [log]}
        (runner/run-once sold-out 0 (counting-ids 2000) within truck-2)
        unload (last log)]
    (is (= first-process-event-id (:event/id unload)))
    (is (= (process/derived-command-id conversation :unload)
           (get-in unload [:metadata :causation-id])))
    (is (= conversation (get-in unload [:metadata :correlation-id])))
    (is (= within (:event/occurred-at unload)))))

(deftest this-implementation-records-only-aggregate-facts-test
  (let [sold-out (trading-day 50)
        {:keys [log]}
        (runner/run-until-quiet sold-out 0 (counting-ids 2000) within truck-2)]
    (is (every? #{truck-1 truck-2} (map :stream/id log))
        "the manager derives its state from correlated aggregate events")))

(deftest unexpected-failures-are-not-disguised-as-business-refusals-test
  (let [unknown (command unknown-command-id
                         :freeze-truck
                         conversation
                         {:truck-id truck-1})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                          (runner/dispatch (trading-day 50)
                                           (counting-ids 2000)
                                           within
                                           unknown))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (runner/handle []
                                       (constantly "not-a-uuid")
                                       t0
                                       (command load-empty-truck-command-id
                                                :load-truck
                                                setup-conversation
                                                {:truck-id truck-1
                                                 :flavour "vanilla"
                                                 :quantity 1})))))

(deftest nothing-new-and-no-active-process-means-nothing-happens-test
  (let [sold-out (trading-day 50)
        completed (runner/run-until-quiet sold-out
                                          0
                                          (counting-ids 2000)
                                          within
                                          truck-2)
        quiet (runner/run-once (:log completed)
                               (:checkpoint completed)
                               (counting-ids 3000)
                               beyond
                               truck-2)]
    (is (= (:log completed) (:log quiet)))))
