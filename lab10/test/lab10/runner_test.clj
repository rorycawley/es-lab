(ns lab10.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab10.policy :as policy]
            [lab10.runner :as runner]
            [lab10.store :as store]
            [lab10.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- gen-id [] (random-uuid))

(defn- load-truck [flavour quantity]
  {:command/id   (random-uuid)
   :command/type :load-truck
   :data         {:truck-id truck-1 :flavour flavour :quantity quantity}})

(defn- buy [flavour]
  {:command/id   (random-uuid)
   :command/type :buy-flavour
   :data         {:truck-id truck-1 :flavour flavour}})

(defn- stock-of [log]
  (truck/replay (store/stream log truck-1)))

;; One cone loaded, then sold. Selling the last one emits `stock-depleted`,
;; which is what the policy reacts to.
(def sold-out
  (-> []
      (runner/handle gen-id (load-truck "vanilla" 1))
      (runner/handle gen-id (buy "vanilla"))))

(deftest the-setup-leaves-an-empty-truck-test
  (is (= [:truck-loaded :flavour-sold :stock-depleted]
         (map :event/type sold-out)))
  (is (= {"vanilla" 0} (stock-of sold-out))))

(deftest the-policy-closes-the-loop-test
  (testing "the reactor sees the depletion and the truck ends up restocked"
    (let [{:keys [log commands]} (runner/run-once sold-out 0 gen-id)]
      (is (= 1 (count commands)))
      (is (= :load-truck (:command/type (first commands))))
      (is (= {"vanilla" policy/restock-quantity} (stock-of log))))))

(deftest events-record-the-command-that-caused-them-test
  (let [{:keys [log commands]} (runner/run-once sold-out 0 gen-id)
        restock (last log)]
    (is (= :truck-loaded (:event/type restock)))
    (is (= (:command/id (first commands))
           (get-in restock [:metadata :causation-id])))))

(deftest the-checkpoint-moves-to-what-was-read-not-to-the-end-test
  (testing "dispatching appends, so those two are different numbers"
    (let [{:keys [log checkpoint]} (runner/run-once sold-out 0 gen-id)]
      (is (= 3 checkpoint) "the three events that existed when the batch was read")
      (is (= 4 (store/last-position log)) "the restock landed after")
      (testing "checkpointing at the end would have skipped an event"
        (is (< checkpoint (store/last-position log)))))))

(deftest a-redelivered-batch-does-not-restock-twice-test
  (testing "the crash case: acted, then died before writing the checkpoint"
    (let [first-pass  (:log (runner/run-once sold-out 0 gen-id))
          second-pass (:log (runner/run-once first-pass 0 gen-id))]
      (is (= {"vanilla" policy/restock-quantity} (stock-of second-pass)))
      (is (= (count first-pass) (count second-pass))
          "the second pass appended nothing")))
  (testing "because the derived command id was already in the log"
    (let [{:keys [log commands]} (runner/run-once sold-out 0 gen-id)]
      (is (store/caused-by? log (:command/id (first commands)))))))

(deftest an-undelivered-command-is-not-mistaken-for-a-delivered-one-test
  (let [{:keys [log]} (runner/run-once sold-out 0 gen-id)]
    (is (not (store/caused-by? log (random-uuid))))))

(deftest the-reactor-settles-test
  (testing "it terminates because the policy ignores what its own command produced"
    (let [{:keys [passes log]} (runner/run-until-quiet sold-out 0 gen-id)]
      (is (= 1 passes) "one productive pass, then quiet")
      (is (= {"vanilla" policy/restock-quantity} (stock-of log)))))
  (testing "the policy's trigger set and its output set do not overlap"
    (is (seq (policy/react {:event/id (random-uuid)
                            :event/type :stock-depleted
                            :stream/id truck-1
                            :data {:flavour "vanilla"}})))
    (is (empty? (policy/react {:event/id (random-uuid) :event/type :truck-loaded})))))

(deftest a-self-triggering-policy-would-not-settle-test
  (testing "which is why run-until-quiet has a bound rather than a while loop"
    (with-redefs [policy/react (fn [event]
                                 (if (#{:truck-loaded :stock-depleted} (:event/type event))
                                   [(load-truck "vanilla" 1)]
                                   []))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not settle"
                            (runner/run-until-quiet sold-out 0 gen-id 5))))))

(deftest nothing-new-means-nothing-happens-test
  (let [{:keys [log checkpoint]} (runner/run-once sold-out 0 gen-id)
        quiet (runner/run-once log checkpoint gen-id)]
    (is (= [] (:commands quiet)))
    (is (= log (:log quiet)))))
