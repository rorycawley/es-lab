(ns lab23.app-test
  "Business behaviour through primary ports with in-memory driven fakes."
  (:require [clojure.test :refer [deftest is]]
            [lab23.adapter.clock :as clock]
            [lab23.app :as app]
            [lab23.port.driven :as driven]
            [lab23.system :as system]))
(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")
(defn- command [type data] {:command/id (random-uuid) :command/type type :data data})
(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))
(deftest loading-stock-is-a-use-case-test
  (with-app (fn [a]
              (let [events (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 3}))]
                (is (= [:truck-loaded] (map :event/type events)))
                (is (= {"vanilla" 3} (app/stock a truck-1)))))))
(deftest selling-the-last-cone-is-two-facts-test
  (with-app (fn [a]
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
              (let [events (app/handle a truck-1 (command :buy-flavour {:flavour "vanilla"}))]
                (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
                (is (= {"vanilla" 0} (app/stock a truck-1)))))))
(deftest a-refused-sale-leaves-business-state-unchanged-test
  (with-app (fn [a]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                                    (app/handle a truck-1 (command :buy-flavour {:flavour "vanilla"}))))
              (is (= {} (app/stock a truck-1))))))
(deftest only-depletion-produces-outgoing-messages-test
  (with-app (fn [a]
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 5}))
              (app/handle a truck-1 (command :buy-flavour {:flavour "vanilla"}))
              (is (empty? (driven/pending (:outbox a))))
              (dotimes [_ 4] (app/handle a truck-1 (command :buy-flavour {:flavour "vanilla"})))
              (is (= #{:customer-app :purchasing}
                     (set (map :recipient (driven/pending (:outbox a)))))))))
(deftest the-policy-restocks-a-depleted-truck-test
  (with-app (fn [a]
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
              (app/handle a truck-1 (command :buy-flavour {:flavour "vanilla"}))
              (let [{:keys [commands]} (app/react a 0 truck-1)]
                (is (= [:load-truck] (map :command/type commands)))
                (is (= {"vanilla" 20} (app/stock a truck-1)))))))
(deftest rerunning-with-nothing-new-does-nothing-test
  (with-app (fn [a]
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 2}))
              (let [{:keys [checkpoint]} (app/react a 0 truck-1)]
                (is (empty? (:commands (app/react a checkpoint truck-1))))))))
