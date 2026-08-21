(ns lab14.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab14.process :as process]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def conversation #uuid "cc79c083-0000-4000-8000-000000000001")

(def t0 #inst "2026-08-16T09:00:00.000-00:00")
(def within #inst "2026-08-16T09:20:00.000-00:00")
(def deadline #inst "2026-08-16T09:30:00.000-00:00")
(def beyond #inst "2026-08-16T09:31:00.000-00:00")

(def depleted
  {:event/type :stock-depleted :event/occurred-at t0
   :stream/id truck-1 :data {:flavour "vanilla"}})

(def unloaded
  {:event/type :flavour-unloaded :stream/id truck-2
   :data {:flavour "vanilla" :quantity process/transfer-quantity}})

(def load-refused
  {:event/type :load-refused :stream/id truck-1
   :data {:flavour "vanilla" :reason "no-room"}})

(def returned
  {:event/type :flavour-returned :stream/id truck-2
   :data {:flavour "vanilla" :quantity process/transfer-quantity}})

(def stock-return-refused
  {:event/type :stock-return-refused :stream/id truck-2
   :data {:flavour "vanilla" :reason "no-room-to-return"}})

(defn- status [events] (:status (process/replay events)))

(deftest a-process-with-no-history-has-not-started-test
  (is (= {:status :not-started} (process/replay [])))
  (is (= [] (process/decide (process/replay []) conversation truck-2 t0))))

(deftest the-happy-path-still-completes-test
  (is (= :complete (status [depleted unloaded
                            {:event/type :truck-loaded :stream/id truck-1
                             :data {:flavour "vanilla" :quantity 10}}]))))

(deftest a-refused-load-puts-the-process-into-compensating-test
  (is (= :awaiting-load (status [depleted unloaded])))
  (is (= :compensating (status [depleted unloaded load-refused]))))

(deftest compensation-completes-the-process-differently-test
  (is (= :compensated (status [depleted unloaded load-refused returned])))
  (testing "which is not the same as never having started"
    (is (not= :not-started (status [depleted unloaded load-refused returned])))))

(deftest a-failed-compensation-needs-a-human-test
  (is (= :needs-attention
         (status [depleted unloaded load-refused stock-return-refused]))))

(deftest the-fold-ignores-known-context-and-rejects-unknown-semantics-test
  (is (= :awaiting-unload
         (status [{:event/type :flavour-sold} depleted])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (process/replay [{:event/type :freezer-failed}]))))

(deftest the-fold-remembers-who-gave-the-stock-up-test
  (testing "compensation has to know where to put it back, and history says"
    (let [state (process/replay [depleted unloaded load-refused])]
      (is (= truck-2 (:donor state)))
      (is (= process/transfer-quantity (:quantity state))))))

(deftest the-compensating-step-asks-the-donor-to-take-it-back-test
  (let [state (process/replay [depleted unloaded load-refused])
        [c]   (process/decide state conversation truck-2 within)]
    (is (= :return-stock (:command/type c)))
    (is (= truck-2 (get-in c [:data :truck-id])) "back where it came from")
    (is (= process/transfer-quantity (get-in c [:data :quantity])))))

(deftest a-return-is-not-a-delivery-test
  (testing "the command that undoes a step is its own command, not a re-run"
    (let [compensating (process/replay [depleted unloaded load-refused])
          loading      (process/replay [depleted unloaded])]
      (is (not= (:command/type (first (process/decide compensating conversation truck-2 within)))
                (:command/type (first (process/decide loading conversation truck-2 within))))))))

(deftest every-terminal-state-asks-for-nothing-test
  (doseq [events [[depleted unloaded load-refused returned]
                  [depleted unloaded load-refused stock-return-refused]
                  [depleted {:event/type :transfer-abandoned :stream/id truck-1 :data {}}]]]
    (is (= [] (process/decide (process/replay events) conversation truck-2 within)))))

(deftest an-abandoned-transfer-needs-no-compensation-test
  (testing "nothing had happened yet, which is why failing early is cheaper"
    (let [state (process/replay [depleted
                                 {:event/type :transfer-abandoned
                                  :stream/id truck-1 :data {}}])]
      (is (= :abandoned (:status state)))
      (is (nil? (:donor state)) "no step completed, so nothing to undo"))))

(deftest the-timeout-includes-the-deadline-test
  (let [state (process/replay [depleted])]
    (is (not (process/timeout-reached? state within)))
    (is (process/timeout-reached? state deadline))
    (is (process/timeout-reached? state beyond))))

(deftest command-identity-is-stable-valid-and-distinct-per-step-test
  (is (= (process/derived-command-id conversation :return)
         (process/derived-command-id conversation :return)))
  (is (= 4 (count (distinct (map #(process/derived-command-id conversation %)
                                 [:unload :load :return :abandon])))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid correlation id"
                        (process/derived-command-id nil :return)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid process step"
                        (process/derived-command-id conversation "return"))))

(deftest only-waiting-states-are-active-test
  (is (process/active? (process/replay [depleted])))
  (is (process/active? (process/replay [depleted unloaded])))
  (is (process/active? (process/replay [depleted unloaded load-refused])))
  (is (not (process/active? (process/replay []))))
  (is (not (process/active? (process/replay [depleted unloaded load-refused returned])))))

(deftest an-unknown-process-status-is-not-treated-as-terminal-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown process status"
                        (process/decide {:status :wedged}
                                        conversation truck-2 within))))
