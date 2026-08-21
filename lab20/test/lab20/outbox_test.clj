(ns lab20.outbox-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [lab20.fixture :as fixture]
            [lab20.handler :as handler]
            [lab20.ledger :as ledger]
            [lab20.outbox :as outbox]
            [lab20.store :as store]
            [next.jdbc :as jdbc]))

(use-fixtures :each fixture/with-store)

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- go! [ds cmd] (handler/handle! ds truck-1 gen-id t0 cmd))

;; ---------------------------------------------------------------------------
;; One transaction, everything in it
;; ---------------------------------------------------------------------------

(deftest the-fact-and-the-message-commit-together-test
  (let [ds (fixture/datasource)]
    (go! ds (command :load-truck {:flavour "vanilla" :quantity 1}))
    (go! ds (command :buy-flavour {:flavour "vanilla"}))
    (testing "the sale emptied the truck, so two modules are told"
      (is (= [:truck-loaded :flavour-sold :stock-depleted]
             (map :event/type (store/stream ds truck-1))))
      (is (= ["flavour-unavailable" "restock-required"]
             (map :message-type (outbox/all ds)))))))

(deftest a-failed-command-enqueues-nothing-test
  (testing "no partial state: no events, no ledger row, no outbox row"
    (let [ds (fixture/datasource)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (go! ds (command :buy-flavour {:flavour "vanilla"}))))
      (is (empty? (store/stream ds truck-1)))
      (is (empty? (outbox/all ds))))))

(deftest most-facts-are-announced-to-nobody-test
  (let [ds (fixture/datasource)]
    (go! ds (command :load-truck {:flavour "vanilla" :quantity 5}))
    (go! ds (command :buy-flavour {:flavour "vanilla"}))
    (testing "a loading and an ordinary sale interest no other module"
      (is (= 2 (count (store/stream ds truck-1))))
      (is (empty? (outbox/all ds))))))

;; ---------------------------------------------------------------------------
;; The command ledger, and the hole it closes in lab 10
;; ---------------------------------------------------------------------------

(deftest a-repeated-command-does-nothing-twice-test
  (let [ds  (fixture/datasource)
        cmd (command :load-truck {:flavour "vanilla" :quantity 3})]
    (go! ds cmd)
    (is (= :already-handled (go! ds cmd)))
    (is (= 1 (count (store/stream ds truck-1))))))

(deftest the-ledger-records-commands-that-produced-nothing-test
  (testing "the case lab 10's causation check cannot see"
    (let [ds  (fixture/datasource)
          cmd (command :load-truck {:flavour "vanilla" :quantity 0})]
      (is (= [] (go! ds cmd)) "loading nothing is not a fact (lab 5)")
      (is (empty? (store/stream ds truck-1)))
      (testing "no event carries this command's causation id"
        (is (zero? (:count (jdbc/execute-one!
                            ds ["SELECT count(*) AS count FROM event
                                 WHERE metadata->>'causation-id' = ?"
                                (str (:command/id cmd))])))))
      (testing "so a causation-based check would re-run it forever"
        (is (some? (ledger/entry ds (:command/id cmd))) "the ledger saw it anyway")
        (is (zero? (:event-count (ledger/entry ds (:command/id cmd)))))
        (is (= :already-handled (go! ds cmd)))))))

(deftest the-ledger-row-shares-the-commands-transaction-test
  (let [ds  (fixture/datasource)
        cmd (command :load-truck {:flavour "vanilla" :quantity 2})]
    (go! ds cmd)
    (is (= 1 (:event-count (ledger/entry ds (:command/id cmd)))))
    (testing "and a refused command leaves no ledger row either"
      (let [bad (command :buy-flavour {:flavour "pistachio"})]
        (is (thrown? clojure.lang.ExceptionInfo (go! ds bad)))
        (is (nil? (ledger/entry ds (:command/id bad))))))))
