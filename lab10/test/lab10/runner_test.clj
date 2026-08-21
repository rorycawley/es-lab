(ns lab10.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab10.policy :as policy]
            [lab10.runner :as runner]
            [lab10.store :as store]
            [lab10.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(def initial-load-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001001")

(def initial-buy-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001002")

(def unrelated-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001003")

(def depletion-event-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001004")

(def loaded-event-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001005")

(def first-reactor-event-id
  #uuid "018f7a3e-0000-7000-8000-000000002001")

(defn- counting-ids
  "A deterministic test adapter for the application's id generator."
  [start]
  (let [counter (atom start)]
    (fn []
      (java.util.UUID/fromString
       (format "018f7a3e-0000-7000-8000-%012d" (swap! counter inc))))))

(defn- load-truck [command-id flavour quantity]
  {:command/id   command-id
   :command/type :load-truck
   :data         {:truck-id truck-1 :flavour flavour :quantity quantity}})

(defn- buy [command-id flavour]
  {:command/id   command-id
   :command/type :buy-flavour
   :data         {:truck-id truck-1 :flavour flavour}})

(defn- stock-of [log]
  (truck/replay (store/stream log truck-1)))

(defn- sold-out-log []
  ;; One cone loaded, then sold. Selling the last one emits `stock-depleted`,
  ;; which is what the policy reacts to.
  (let [gen-id (counting-ids 1000)]
    (-> []
        (runner/handle gen-id
                       (load-truck initial-load-command-id "vanilla" 1))
        (runner/handle gen-id
                       (buy initial-buy-command-id "vanilla")))))

(deftest the-setup-leaves-an-empty-truck-test
  (let [sold-out (sold-out-log)]
    (is (= [:truck-loaded :flavour-sold :stock-depleted]
           (map :event/type sold-out)))
    (is (= {"vanilla" 0} (stock-of sold-out)))))

(deftest the-policy-closes-the-loop-test
  (testing "the reactor sees the depletion and the truck ends up restocked"
    (let [sold-out (sold-out-log)
          gen-id   (counting-ids 2000)
          {:keys [log commands]} (runner/run-once sold-out 0 gen-id)]
      (is (= 1 (count commands)))
      (is (= :load-truck (:command/type (first commands))))
      (is (= {"vanilla" policy/restock-quantity} (stock-of log))))))

(deftest events-record-the-command-that-caused-them-test
  (let [sold-out (sold-out-log)
        gen-id   (counting-ids 2000)
        {:keys [log commands]} (runner/run-once sold-out 0 gen-id)
        restock  (last log)]
    (is (= first-reactor-event-id (:event/id restock))
        "the store preserves the identity supplied by the application")
    (is (= :truck-loaded (:event/type restock)))
    (is (= (:command/id (first commands))
           (get-in restock [:metadata :causation-id])))))

(deftest the-checkpoint-moves-to-what-was-read-not-to-the-end-test
  (testing "dispatching appends, so those two are different numbers"
    (let [sold-out (sold-out-log)
          {:keys [log checkpoint]}
          (runner/run-once sold-out 0 (counting-ids 2000))]
      (is (= 3 checkpoint) "the three events that existed when the batch was read")
      (is (= 4 (store/last-position log)) "the restock landed after")
      (testing "checkpointing at the end would have skipped an event"
        (is (< checkpoint (store/last-position log)))))))

(deftest a-redelivered-batch-does-not-restock-twice-test
  (let [sold-out (sold-out-log)]
    (testing "the crash case: acted, then died before writing the checkpoint"
      (let [first-pass  (:log (runner/run-once sold-out 0 (counting-ids 2000)))
            second-pass (:log (runner/run-once first-pass 0 (counting-ids 3000)))]
        (is (= {"vanilla" policy/restock-quantity} (stock-of second-pass)))
        (is (= (count first-pass) (count second-pass))
            "the second pass appended nothing")))
    (testing "because the derived command id was already in the log"
      (let [{:keys [log commands]}
            (runner/run-once sold-out 0 (counting-ids 2000))]
        (is (store/caused-by? log (:command/id (first commands))))))))

(deftest an-undelivered-command-is-not-mistaken-for-a-delivered-one-test
  (let [{:keys [log]}
        (runner/run-once (sold-out-log) 0 (counting-ids 2000))]
    (is (not (store/caused-by? log unrelated-command-id)))))

(deftest the-reactor-settles-test
  (testing "it terminates because the policy ignores what its own command produced"
    (let [{:keys [passes log]}
          (runner/run-until-quiet (sold-out-log) 0 (counting-ids 2000))]
      (is (= 1 passes) "one productive pass, then quiet")
      (is (= {"vanilla" policy/restock-quantity} (stock-of log)))))
  (testing "the real policy's trigger set and output-event set do not overlap"
    (is (seq (policy/react {:event/id depletion-event-id
                            :event/type :stock-depleted
                            :stream/id truck-1
                            :data {:flavour "vanilla"}})))
    (is (empty? (policy/react {:event/id loaded-event-id
                               :event/type :truck-loaded})))))

(deftest nothing-new-means-nothing-happens-test
  (let [sold-out (sold-out-log)
        {:keys [log checkpoint]}
        (runner/run-once sold-out 0 (counting-ids 2000))
        quiet (runner/run-once log checkpoint (counting-ids 3000))]
    (is (= [] (:commands quiet)))
    (is (= log (:log quiet)))))

(deftest the-domain-rejects-event-semantics-it-does-not-know-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}]))))
