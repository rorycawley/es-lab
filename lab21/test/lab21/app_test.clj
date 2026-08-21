(ns lab21.app-test
  "One suite, two adapters.

  A port with a single implementation is not a boundary — it is indirection
  with optimism attached. These tests run identically against a map in an atom
  and against Postgres in a container, and the fact that they *can* is the only
  evidence that the boundary is real.

  Note what the tests never mention: `next.jdbc`, a datasource, a container, a
  transaction. They are written against the application layer, which is written
  against ports."
  (:require [clojure.test :refer [deftest is testing]]
            [lab21.adapter.clock :as clock]
            [lab21.app :as app]
            [lab21.core.contract :as contract]
            [lab21.core.truck :as truck]
            [lab21.fixture :as fixture]
            [lab21.port :as port]
            [lab21.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- command [type data]
  {:command/id (random-uuid) :command/type type :data data})

(defn- each-adapter
  "Run `f` against every adapter, so a failure names which one broke."
  [f]
  (doseq [[label make-system] (fixture/systems {:clock (clock/fixed-clock t0)})]
    (testing (str "against " label)
      (let [sys (system/start (make-system))]
        (try (f (system/app sys))
             (finally (system/stop sys)))))))

;; ---------------------------------------------------------------------------

(deftest a-command-becomes-events-test
  (each-adapter
   (fn [app]
     (let [events (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 3}))]
       (is (= [:truck-loaded] (map :event/type events)))
       (is (= [1] (map :stream/version events)))
       (is (= {"vanilla" 3} (app/stock app truck-1)))))))

(deftest selling-the-last-cone-is-two-facts-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
     (let [events (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"}))]
       (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
       (is (= {"vanilla" 0} (app/stock app truck-1)))))))

(deftest a-refused-command-records-nothing-test
  (each-adapter
   (fn [app]
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                           (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"}))))
     (is (empty? (port/read-stream (:store app) truck-1))))))

(deftest a-stale-version-is-refused-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 2}))
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Concurrent modification"
          (port/append (:store app) truck-1 0 (command :load-truck {})
                       [{:event/type :truck-loaded :event/id (random-uuid)
                         :event/occurred-at t0 :data {:flavour "vanilla" :quantity 1}}]))))))

(deftest only-a-depletion-is-announced-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 5}))
     (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"}))
     (is (empty? (port/pending (:outbox app))) "an ordinary sale interests nobody")
     (dotimes [_ 4] (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"})))
     (is (= 2 (count (port/pending (:outbox app)))) "the depletion reaches two modules"))))

(deftest the-policy-closes-the-loop-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
     (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"}))
     (let [{:keys [commands]} (app/react app 0 truck-1)]
       (is (= [:load-truck] (map :command/type commands)))
       (is (= {"vanilla" 20} (app/stock app truck-1)))))))

(deftest nothing-new-means-nothing-happens-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 2}))
     (let [{:keys [checkpoint]} (app/react app 0 truck-1)
           quiet (app/react app checkpoint truck-1)]
       (is (empty? (:commands quiet)))))))

;; ---------------------------------------------------------------------------
;; The core is testable without any of this
;; ---------------------------------------------------------------------------

(deftest the-core-needs-no-system-at-all-test
  (testing "no adapter, no component, no fixture — just values"
    (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}
            {:event/type :stock-depleted :data {:flavour "vanilla"}}]
           (truck/decide (command :buy-flavour {:flavour "vanilla"})
                         {"vanilla" 1})))
    (is (= "customer-app ← flavour-unavailable (vanilla)"
           (contract/describe
            (first (contract/announce {:event/type :stock-depleted
                                       :event/id (random-uuid)
                                       :stream/id truck-1
                                       :data {:flavour "vanilla"}})))))))
