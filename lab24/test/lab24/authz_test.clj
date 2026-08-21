(ns lab24.authz-test
  "Authorisation, split across the places ADR-0020 puts it.

    RBAC          may this role issue this kind of command   `authority`
    ABAC          may this user act on this thing            `core/truck`
    field-level   what may this role see                     the query adapter
    row-level     which rows may this role see               the query adapter

  The first two are exercised through `intake_test`, where the refusals live.
  This namespace covers what is left: that the two tables agree, that reading
  is shaped by role, and the one that took the longest to see — that authority
  does not travel along a causal chain."
  (:require [clojure.test :refer [deftest is testing]]
            [lab24.adapter.clock :as clock]
            [lab24.adapter.intake :as intake]
            [lab24.app :as app]
            [lab24.authority :as authority]
            [lab24.core.policy :as policy]
            [lab24.port.driven :as driven]
            [lab24.schema.command :as command]
            [lab24.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(def dana {:actor {:type "user" :id "USR-83721"} :roles #{:driver}})
(def rudi {:actor {:type "user" :id "USR-11902"} :roles #{:depot}})

(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))

;; ---------------------------------------------------------------------------
;; The permission table and the command vocabulary are one list
;; ---------------------------------------------------------------------------

(deftest every-command-has-a-role-that-may-issue-it-test
  (testing "a command nobody may issue is either dead or an accident"
    (doseq [command-type (keys command/by-type)]
      (is (contains? authority/all-command-types command-type)
          (str command-type " appears in no role's permission set")))))

(deftest every-permission-names-a-real-command-test
  (testing "and a permission for a command that no longer exists is a stale grant"
    (doseq [command-type authority/all-command-types]
      (is (contains? command/by-type command-type)
          (str command-type " is granted to a role but is not a command type")))))

(deftest roles-do-not-overlap-by-accident-test
  (is (false? (authority/permits? #{:driver} :load-truck)))
  (is (false? (authority/permits? #{:depot} :buy-flavour)))
  (is (true?  (authority/permits? #{:driver} :buy-flavour)))
  (is (true?  (authority/permits? #{:depot} :assign-driver)))
  (testing "no roles at all permits nothing"
    (is (false? (authority/permits? #{} :buy-flavour)))))

;; ---------------------------------------------------------------------------
;; Authority does not propagate
;; ---------------------------------------------------------------------------

(deftest a-policy-stamps-its-own-actor-test
  (testing "the command a policy asks for is not authorised by whoever caused it"
    (let [depletion {:event/type :stock-depleted
                     :event/id   (random-uuid)
                     :stream/id  truck-1
                     :metadata   {:actor {:type "user" :id "USR-83721"}}
                     :data       {:flavour "vanilla"}}
          [restock] (policy/react depletion)]
      (is (= {:type "system" :id "restock-when-depleted"} (:command/actor restock))
          "the customer who bought the last cone did not order a restock"))))

(deftest the-recorded-actor-changes-when-the-reactor-takes-over-test
  (with-app
    (fn [app]
      (intake/submit app truck-1 rudi {:type :assign-driver :data {:driver-id "USR-83721"}})
      (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 1}})
      (intake/submit app truck-1 dana {:type :buy-flavour :data {:flavour "vanilla"}})
      (app/react app 0 truck-1)

      (let [actors (map (comp :actor :metadata) (driven/read-stream (:store app) truck-1))]
        (is (= [{:type "user" :id "USR-11902"}            ; Rudi rostered
                {:type "user" :id "USR-11902"}            ; Rudi loaded
                {:type "user" :id "USR-83721"}            ; Dana sold
                {:type "user" :id "USR-83721"}            ; and depleted it
                {:type "system" :id "restock-when-depleted"}] ; the policy restocked
               actors)
            "the chain is continuous; the authority behind it is not")))))

(deftest correlation-travels-where-authority-does-not-test
  (testing "the causal link survives the handover the actor does not"
    (let [depletion {:event/type :stock-depleted :event/id (random-uuid)
                     :stream/id truck-1 :data {:flavour "vanilla"}}
          [restock] (policy/react depletion)]
      (is (= (policy/derived-command-id :restock-when-depleted depletion)
             (:command/id restock))
          "still derived from the triggering event (lab 10)")
      (is (not= {:type "user" :id "USR-83721"} (:command/actor restock))
          "but not performed by the person who triggered it"))))

;; ---------------------------------------------------------------------------
;; Why the second layer has to exist
;; ---------------------------------------------------------------------------

(deftest the-edge-gate-is-bypassable-and-the-decide-gate-is-not-test
  (with-app
    (fn [app]
      (intake/submit app truck-1 rudi {:type :assign-driver :data {:driver-id "USR-83721"}})
      (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 1}})
      (intake/submit app truck-1 dana {:type :buy-flavour :data {:flavour "vanilla"}})

      (testing "the system principal holds no role, so the RBAC gate would refuse it"
        (is (false? (authority/permits? #{} :load-truck))))

      (testing "and the restock happens anyway, because the reactor is not a caller"
        (let [{:keys [commands]} (app/react app 0 truck-1)]
          (is (= [:load-truck] (map :command/type commands)))
          (is (= {"vanilla" 20} (app/stock app truck-1)))))

      (testing "which is the argument for putting ownership in `decide` instead"
        ;; A gate at the door protects the door. `decide` is on the only path
        ;; there is, so a rule there holds for the reactor, a queue consumer, a
        ;; migration and a REPL alike.
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Not this truck's driver"
             (app/handle app truck-1 {:command/id (random-uuid)
                                      :command/type :buy-flavour
                                      :command/actor {:type "system" :id "some-job"}
                                      :data {:flavour "vanilla"}})))))))

;; ---------------------------------------------------------------------------
;; Field-level security: same events, different views
;; ---------------------------------------------------------------------------

(deftest a-read-is-shaped-by-who-is-reading-test
  (with-app
    (fn [app]
      (intake/submit app truck-1 rudi {:type :assign-driver :data {:driver-id "USR-83721"}})
      (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 4}})

      (is (= {"vanilla" 4} (app/stock app truck-1))
          "a driver is told what is on the truck")
      (is (= {:stock {"vanilla" 4} :driver "USR-83721"} (app/operations app truck-1))
          "and the depot is also told who is driving it")

      (testing "both are folds of the same events, which is the whole trick"
        (is (= (:stock (app/operations app truck-1))
               (app/stock app truck-1)))))))
