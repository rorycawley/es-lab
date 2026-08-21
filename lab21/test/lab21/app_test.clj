(ns lab21.app-test
  "Business behaviour through the driving/input ports.

  These tests know the requests a caller can make and the outcomes the truck
  promises. They do not know which pure functions, aggregates or helper
  namespaces produce those outcomes. The real inner hexagon runs as one unit.

  Driven infrastructure is replaced by the in-memory adapters. They are fakes,
  not interaction mocks: no test asserts which internal function was called or
  how many SQL-shaped operations occurred. Success is observed in returned
  facts, public query state and outgoing messages."
  (:require [clojure.test :refer [deftest is]]
            [lab21.adapter.clock :as clock]
            [lab21.app :as app]
            [lab21.port :as port]
            [lab21.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try
      (f (system/app sys))
      (finally (system/stop sys)))))

(deftest loading-stock-is-a-use-case-test
  (with-app
    (fn [application]
      (let [events (app/handle application truck-1
                               (command :load-truck
                                        {:flavour "vanilla" :quantity 3}))]
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
                                        (command :buy-flavour
                                                 {:flavour "vanilla"}))))
      (is (= {} (app/stock application truck-1))))))

(deftest only-depletion-produces-outgoing-messages-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 5}))
      (app/handle application truck-1
                  (command :buy-flavour {:flavour "vanilla"}))
      (is (empty? (port/pending (:outbox application)))
          "an ordinary sale interests nobody")
      (dotimes [_ 4]
        (app/handle application truck-1
                    (command :buy-flavour {:flavour "vanilla"})))
      (is (= #{:customer-app :purchasing}
             (set (map :recipient (port/pending (:outbox application)))))
          "depletion announces the business outcome, not internal calls"))))

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

(deftest a-valid-no-op-command-is-still-idempotent-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 3}))
      (let [cmd (command :ensure-stock {:flavour "vanilla" :quantity 3})]
        (is (= [] (app/handle application truck-1 cmd)))
        (is (= [] (app/handle application truck-1 cmd)))
        (is (= {"vanilla" 3} (app/stock application truck-1)))))))

(deftest a-retried-sale-returns-its-original-result-before-redeciding-test
  (with-app
    (fn [application]
      (app/handle application truck-1
                  (command :load-truck {:flavour "vanilla" :quantity 1}))
      (let [sale (command :buy-flavour {:flavour "vanilla"})
            first-result (app/handle application truck-1 sale)
            retry-result (app/handle application truck-1 sale)]
        (is (= first-result retry-result))
        (is (= {"vanilla" 0} (app/stock application truck-1)))
        (is (= 2 (count (port/pending (:outbox application)))))))))

(deftest the-reactor-routes-each-command-to-the-facts-own-stream-test
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
