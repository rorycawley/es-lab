(ns lab24.app-test
  "Business behaviour through primary ports with in-memory driven fakes.

  The domain and application collaborate for real. Tests observe facts, query
  state, outgoing messages and audit metadata—not internal call sequences."
  (:require [clojure.test :refer [deftest is]]
            [lab24.adapter.clock :as clock]
            [lab24.app :as app]
            [lab24.port.driven :as driven]
            [lab24.system :as system]))
(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")
(def dana {:type "user" :id "USR-83721"})
(def rudi {:type "user" :id "USR-11902"})
(defn- command
  ([type data] (command rudi type data))
  ([actor type data]
   {:command/id (random-uuid) :command/type type
    :correlation-id (random-uuid) :command/actor actor :data data}))
(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))
(defn- rostered [a]
  (app/handle a truck-1 (command :assign-driver {:driver-id (:id dana)})) a)

(deftest loading-stock-is-a-use-case-test
  (with-app (fn [a]
              (let [events (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 3}))]
                (is (= [:truck-loaded] (map :event/type events)))
                (is (= {"vanilla" 3} (app/stock a truck-1)))))))
(deftest selling-the-last-cone-is-two-facts-test
  (with-app (fn [a]
              (rostered a)
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
              (let [events (app/handle a truck-1 (command dana :buy-flavour {:flavour "vanilla"}))]
                (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
                (is (= {"vanilla" 0} (app/stock a truck-1)))))))
(deftest a-refused-sale-leaves-business-state-unchanged-test
  (with-app (fn [a]
              (rostered a)
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                                    (app/handle a truck-1
                                                (command dana :buy-flavour {:flavour "vanilla"}))))
              (is (= {} (app/stock a truck-1))))))
(deftest only-depletion-produces-outgoing-messages-test
  (with-app (fn [a]
              (rostered a)
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 5}))
              (app/handle a truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
              (is (empty? (driven/pending (:outbox a))))
              (dotimes [_ 4]
                (app/handle a truck-1 (command dana :buy-flavour {:flavour "vanilla"})))
              (is (= #{:customer-app :purchasing}
                     (set (map :recipient (driven/pending (:outbox a)))))))))
(deftest the-policy-restocks-with-system-authority-test
  (with-app (fn [a]
              (rostered a)
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
              (app/handle a truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
              (let [{:keys [commands]} (app/react a 0)]
                (is (= [:load-truck] (map :command/type commands)))
                (is (= [{:type "system" :id "restock-when-depleted"}]
                       (map :command/actor commands)))
                (is (= {"vanilla" 20} (app/stock a truck-1)))))))
(deftest recorded-facts-identify-the-responsible-actor-test
  (with-app (fn [a]
              (rostered a)
              (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
              (app/handle a truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
              (is (= [rudi rudi dana dana]
                     (map (comp :actor :metadata)
                          (driven/read-stream (:store a) truck-1)))))))

(deftest an-exact-command-retry-returns-the-original-audited-outcome-test
  (with-app
    (fn [a]
      (rostered a)
      (app/handle a truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
      (let [sale (command dana :buy-flavour {:flavour "vanilla"})
            first-result (app/handle a truck-1 sale)]
        (is (= first-result (app/handle a truck-1 sale)))
        (is (= {"vanilla" 0} (app/stock a truck-1)))
        (is (= dana (get-in (first first-result) [:metadata :actor])))
        (is (= 2 (count (driven/pending (:outbox a)))))))))

(deftest the-reactor-routes-system-commands-to-the-fact-stream-test
  (with-app
    (fn [a]
      (let [truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002"]
        (doseq [truck [truck-1 truck-2]]
          (app/handle a truck (command :assign-driver {:driver-id (:id dana)}))
          (app/handle a truck (command :load-truck {:flavour "vanilla" :quantity 1}))
          (app/handle a truck (command dana :buy-flavour {:flavour "vanilla"})))
        (app/react a 0)
        (is (= {"vanilla" 20} (app/stock a truck-1)))
        (is (= {"vanilla" 20} (app/stock a truck-2)))))))
