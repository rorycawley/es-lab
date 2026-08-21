(ns lab22.app-test
  "Business behaviour through the primary ports.

  The real inner hexagon runs as one unit. Driven infrastructure is replaced
  by in-memory fakes, and assertions concern returned facts, public query
  state and outgoing messages—not calls between internal classes."
  (:require [clojure.test :refer [deftest is]]
            [lab22.adapter.clock :as clock]
            [lab22.app :as app]
            [lab22.port :as port]
            [lab22.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")
(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})
(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))

(deftest loading-stock-is-a-use-case-test
  (with-app
    (fn [application]
      (let [events (app/handle application truck-1
                               (command :load-truck {:flavour "vanilla" :quantity 3}))]
        (is (= [:truck-loaded] (map :event/type events)))
        (is (= {"vanilla" 3} (app/stock application truck-1)))))))

(deftest selling-the-last-cone-is-two-facts-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 1}))
      (let [events (app/handle application truck-1
                               (command :buy-flavour {:flavour "vanilla"}))]
        (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
        (is (= {"vanilla" 0} (app/stock application truck-1)))))))

(deftest a-refused-sale-leaves-business-state-unchanged-test
  (with-app
    (fn [application]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                            (app/handle application truck-1
                                        (command :buy-flavour {:flavour "vanilla"}))))
      (is (= {} (app/stock application truck-1))))))

(deftest only-depletion-produces-outgoing-messages-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 5}))
      (app/handle application truck-1
                  (command :buy-flavour {:flavour "vanilla"}))
      (is (empty? (port/pending (:outbox application))))
      (dotimes [_ 4]
        (app/handle application truck-1
                    (command :buy-flavour {:flavour "vanilla"})))
      (is (= #{:customer-app :purchasing}
             (set (map :recipient (port/pending (:outbox application)))))))))

(deftest the-policy-restocks-a-depleted-truck-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 1}))
      (app/handle application truck-1
                  (command :buy-flavour {:flavour "vanilla"}))
      (let [{:keys [commands]} (app/react application 0)]
        (is (= [:load-truck] (map :command/type commands)))
        (is (= {"vanilla" 20} (app/stock application truck-1)))))))

(deftest rerunning-with-nothing-new-does-nothing-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 2}))
      (let [{:keys [checkpoint]} (app/react application 0)
            quiet (app/react application checkpoint)]
        (is (empty? (:commands quiet)))))))

(deftest a-retried-sale-returns-its-original-outcome-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 1}))
      (let [sale (command :buy-flavour {:flavour "vanilla"})
            first-result (app/handle application truck-1 sale)]
        (is (= first-result (app/handle application truck-1 sale)))
        (is (= {"vanilla" 0} (app/stock application truck-1)))
        (is (= 2 (count (port/pending (:outbox application)))))))))

(deftest the-reactor-routes-facts-to-their-own-streams-test
  (with-app
    (fn [application]
      (let [truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002"]
        (doseq [truck [truck-1 truck-2]]
          (app/handle application truck
                      (command :load-truck {:flavour "vanilla" :quantity 1}))
          (app/handle application truck
                      (command :buy-flavour {:flavour "vanilla"})))
        (app/react application 0)
        (is (= {"vanilla" 20} (app/stock application truck-1)))
        (is (= {"vanilla" 20} (app/stock application truck-2)))))))
