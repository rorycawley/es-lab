(ns lab14.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab14.process :as process]
            [lab14.runner :as runner]
            [lab14.store :as store]
            [lab14.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(def t0     #inst "2026-08-16T09:00:00.000-00:00")
(def within #inst "2026-08-16T09:20:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type conversation data]
  {:command/id (random-uuid) :command/type type
   :correlation-id conversation :data data})

(defn- stock-of [log stream-id]
  (truck/replay (store/stream log stream-id)))

(defn- fleet-total
  "Every cone the fleet is holding. Nothing may create or destroy one."
  [log]
  (+ (truck/total-stock (stock-of log truck-1))
     (truck/total-stock (stock-of log truck-2))))

(defn- fleet-vanilla
  [log]
  (+ (get-in (stock-of log truck-1) [:stock "vanilla"] 0)
     (get-in (stock-of log truck-2) [:stock "vanilla"] 0)))

(defn- conversation-of [log] (get-in (last log) [:metadata :correlation-id]))

(defn- types-on [log stream-id] (map :event/type (store/stream log stream-id)))

;; Truck 1 holds capacity 20 and is full of chocolate, with one vanilla it is
;; about to sell. Truck 2 has room and plenty of vanilla.
;;
;; So truck 1 runs out of vanilla and has no room to take any — a fact the
;; process manager has no way to know in advance, because capacity is the
;; truck's business, not the process's.
(defn- trading-day
  [donor-capacity]
  (let [setup (random-uuid)
        sale  (random-uuid)]
    (-> []
        (runner/handle gen-id t0 (command :commission-truck setup
                                          {:truck-id truck-1 :capacity 20}))
        (runner/handle gen-id t0 (command :commission-truck setup
                                          {:truck-id truck-2 :capacity donor-capacity}))
        (runner/handle gen-id t0 (command :load-truck setup
                                          {:truck-id truck-1 :flavour "chocolate" :quantity 19}))
        (runner/handle gen-id t0 (command :load-truck setup
                                          {:truck-id truck-1 :flavour "vanilla" :quantity 1}))
        (runner/handle gen-id t0 (command :load-truck setup
                                          {:truck-id truck-2 :flavour "vanilla" :quantity 30}))
        (runner/handle gen-id t0 (command :buy-flavour sale
                                          {:truck-id truck-1 :flavour "vanilla"})))))

(def sold-out (trading-day 50))
(def conversation (conversation-of sold-out))

(deftest the-setup-leaves-truck-1-empty-of-vanilla-and-full-test
  (is (= 0 (get-in (stock-of sold-out truck-1) [:stock "vanilla"])))
  (is (= 19 (truck/total-stock (stock-of sold-out truck-1))))
  (is (= 20 (:capacity (stock-of sold-out truck-1))))
  (is (= :stock-depleted (:event/type (last sold-out)))))

(deftest the-load-is-refused-and-that-refusal-is-a-fact-test
  (testing "everywhere else a refusal records nothing; here a process needs it"
    (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
      (is (some #(= :load-refused (:event/type %)) log))
      (is (= "no-room" (->> log
                            (filter #(= :load-refused (:event/type %)))
                            first :data :reason))))))

(deftest the-fleet-is-short-while-the-stock-is-in-limbo-test
  (testing "one pass: the donor has given ten up and nobody has them"
    (let [before (fleet-total sold-out)
          after-unload (:log (runner/run-once sold-out 0 gen-id within truck-2))]
      (is (= 49 before) "19 on truck 1, 30 on truck 2")
      (is (= (- before process/transfer-quantity) (fleet-total after-unload))
          "ten cones exist nowhere"))))

(deftest compensation-restores-the-invariant-test
  (let [before (fleet-total sold-out)
        {:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
    (is (= :compensated (:status (process/replay (store/correlated log conversation)))))
    (is (= before (fleet-total log)) "every cone accounted for again")))

(deftest the-undo-is-a-different-fact-from-the-delivery-test
  (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
    (testing "the donor's stream shows a return, not a second delivery"
      (is (= [:truck-commissioned :truck-loaded :flavour-unloaded :flavour-returned]
             (types-on log truck-2))))
    (testing "so the log can tell you this movement was an undo"
      (is (not (some #(= :truck-loaded (:event/type %))
                     (filter #(= :flavour-returned (:event/type %))
                             (store/stream log truck-2))))))))

(deftest the-whole-story-survives-test
  (testing "a rollback leaves no trace; this leaves the attempt and the undo"
    (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)
          history (map :event/type (store/correlated log conversation))]
      (is (= [:flavour-sold :stock-depleted :flavour-unloaded :load-refused :flavour-returned]
             history)))))

(deftest a-compensated-process-is-not-one-that-never-ran-test
  (let [{:keys [log]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
    (is (> (count log) (count sold-out)) "four more facts than before it tried")
    (is (= (fleet-total sold-out) (fleet-total log)) "and the same stock")))

(deftest compensation-can-itself-fail-test
  (testing "a donor with no room left cannot take its own stock back"
    ;; Capacity 30 and 30 cones: the donor is exactly full once it has
    ;; unloaded ten and someone else has filled the gap.
    (let [tight (trading-day 30)
          cid   (conversation-of tight)
          after-unload (:log (runner/run-once tight 0 gen-id within truck-2))
          ;; while the transfer is in flight, the depot tops the donor up
          topped (runner/handle after-unload gen-id within
                                (command :load-truck (random-uuid)
                                         {:truck-id truck-2 :flavour "chocolate" :quantity 10}))
          {:keys [log]} (runner/run-until-quiet topped 0 gen-id within truck-2)]
      (is (= :needs-attention (:status (process/replay (store/correlated log cid)))))
      (is (some #(= :compensation-failed (:event/type %)) log))
      (testing "and the ten vanilla cones are still missing — a human must sort it out"
        (is (= 30 (fleet-vanilla tight)))
        (is (= 20 (fleet-vanilla log)) "ten short, and no automatic route back")))))

(deftest compensating-twice-does-not-return-twice-test
  (testing "derived command ids make the pass idempotent (lab 10)"
    (let [once  (:log (runner/run-until-quiet sold-out 0 gen-id within truck-2))
          twice (:log (runner/run-until-quiet once 0 gen-id within truck-2))]
      (is (= (count once) (count twice)))
      (is (= (fleet-total once) (fleet-total twice))))))

(deftest nothing-new-means-nothing-happens-test
  (let [{:keys [log checkpoint]} (runner/run-until-quiet sold-out 0 gen-id within truck-2)]
    (is (= log (:log (runner/run-once log checkpoint gen-id within truck-2))))))
