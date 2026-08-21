(ns lab24.intake-test
  "The driving edge: every way of saying no, and why they are not one thing.

  This namespace drives beneath HTTP, so a principal is a literal map rather
  than something a token was traded for. That is deliberate — the four
  refusals are decided here, and testing them through a socket would be
  testing the socket."
  (:require [clojure.test :refer [deftest is testing]]
            [lab24.adapter.clock :as clock]
            [lab24.adapter.intake :as intake]
            [lab24.app :as app]
            [lab24.port.driven :as driven]
            [lab24.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(def dana {:actor {:type "user" :id "USR-83721"} :roles #{:driver}})
(def sam  {:actor {:type "user" :id "USR-55010"} :roles #{:driver}})
(def rudi {:actor {:type "user" :id "USR-11902"} :roles #{:depot}})

(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))

(defn- roster [app driver]
  (intake/submit app truck-1 rudi {:type :assign-driver
                                   :data {:driver-id (get-in driver [:actor :id])}}))

;; ---------------------------------------------------------------------------
;; The happy path
;; ---------------------------------------------------------------------------

(deftest a-well-formed-permitted-message-becomes-events-test
  (with-app
    (fn [app]
      (let [result (intake/submit app truck-1 rudi {:type :load-truck
                                                    :data {:flavour "vanilla" :quantity 3}})]
        (is (= [:truck-loaded] (map :event/type (:accepted result))))
        (is (= {"vanilla" 3} (app/stock app truck-1)))))))

;; ---------------------------------------------------------------------------
;; Four refusals, and the differences between them
;; ---------------------------------------------------------------------------

(deftest malformed-and-refused-are-different-answers-test
  (with-app
    (fn [app]
      (roster app dana)

      (testing "malformed: never reaches the domain"
        (let [result (intake/submit app truck-1 rudi {:type :load-truck
                                                      :data {:flavour "tarmac" :quantity 3}})]
          (is (= :malformed (:rejected result)))
          (is (some? (:because result)) "and it says what was wrong")))

      (testing "refused: well-formed, permitted, reached the domain, and it said no"
        (let [result (intake/submit app truck-1 dana {:type :buy-flavour
                                                      :data {:flavour "vanilla"}})]
          (is (= :refused (:rejected result)))
          (is (= "Sold out" (:because result)))))

      (testing "neither records anything — for entirely different reasons"
        (is (= [:driver-assigned]
               (map :event/type (driven/read-stream (:store app) truck-1))))))))

(deftest the-same-message-flips-from-refused-to-accepted-test
  (with-app
    (fn [app]
      (roster app dana)
      (let [buy {:type :buy-flavour :data {:flavour "vanilla"}}]
        (is (= :refused (:rejected (intake/submit app truck-1 dana buy))))
        (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 1}})
        (is (some? (:accepted (intake/submit app truck-1 dana buy)))
            "state changed, so the domain's answer changed")))))

(deftest a-malformed-message-never-flips-test
  (with-app
    (fn [app]
      (roster app dana)
      (let [bad {:type :buy-flavour :data {:flavour "tarmac"}}]
        (is (= :malformed (:rejected (intake/submit app truck-1 dana bad))))
        (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 9}})
        (is (= :malformed (:rejected (intake/submit app truck-1 dana bad)))
            "no amount of state makes tarmac a flavour")))))

(deftest an-unknown-type-stops-at-the-door-test
  (with-app
    (fn [app]
      (is (= :forbidden (:rejected (intake/submit app truck-1 dana {:type :steal-truck :data {}})))
          "no role permits a command that does not exist, so the role gate answers first"))))

;; ---------------------------------------------------------------------------
;; Authorisation is checked before validation, and that ordering is a decision
;; ---------------------------------------------------------------------------

(deftest a-forbidden-caller-learns-nothing-about-the-schema-test
  (with-app
    (fn [app]
      (testing "a driver may not restock, and a garbage body does not change that"
        (let [with-good-body (intake/submit app truck-1 dana
                                            {:type :load-truck
                                             :data {:flavour "vanilla" :quantity 3}})
              with-bad-body  (intake/submit app truck-1 dana
                                            {:type :load-truck
                                             :data {:flavour "tarmac" :quantity "lots"}})]
          (is (= :forbidden (:rejected with-good-body)))
          (is (= :forbidden (:rejected with-bad-body))
              "identical answers, so the endpoint is not a schema oracle")
          (is (= (:because with-good-body) (:because with-bad-body))))))))

;; ---------------------------------------------------------------------------
;; The two halves of authorisation, from one edge
;; ---------------------------------------------------------------------------

(deftest a-role-gate-and-an-ownership-gate-both-say-forbidden-test
  (with-app
    (fn [app]
      (roster app dana)
      (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 5}})

      (testing "RBAC — the depot may restock but may not sell"
        (let [result (intake/submit app truck-1 rudi {:type :buy-flavour
                                                      :data {:flavour "vanilla"}})]
          (is (= :forbidden (:rejected result)))
          (is (= "your role does not permit this command" (:because result)))))

      (testing "ABAC — Sam has exactly the right role and still may not"
        (let [result (intake/submit app truck-1 sam {:type :buy-flavour
                                                     :data {:flavour "vanilla"}})]
          (is (= :forbidden (:rejected result)))
          (is (= "Not this truck's driver" (:because result))
              "same answer, decided in a different place, for a different reason")))

      (testing "and Dana, who is both, may"
        (is (some? (:accepted (intake/submit app truck-1 dana {:type :buy-flavour
                                                               :data {:flavour "vanilla"}}))))))))

(deftest ownership-is-checked-before-availability-test
  (with-app
    (fn [app]
      (roster app dana)
      (testing "an empty truck and a full one refuse Sam identically"
        (let [when-empty (intake/submit app truck-1 sam {:type :buy-flavour
                                                         :data {:flavour "vanilla"}})]
          (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 5}})
          (let [when-stocked (intake/submit app truck-1 sam {:type :buy-flavour
                                                             :data {:flavour "vanilla"}})]
            (is (= (:because when-empty) (:because when-stocked))
                "otherwise any valid token is an inventory oracle")))))))

;; ---------------------------------------------------------------------------
;; The actor comes from the principal and can come from nowhere else
;; ---------------------------------------------------------------------------

(deftest the-body-cannot-supply-an-actor-test
  (with-app
    (fn [app]
      (roster app dana)
      (intake/submit app truck-1 rudi {:type :load-truck :data {:flavour "vanilla" :quantity 2}})

      (testing "a body claiming to be somebody is malformed, because :data is closed"
        (let [result (intake/submit app truck-1 sam
                                    {:type :buy-flavour
                                     :data {:flavour "vanilla"
                                            :actor {:type "user" :id "USR-83721"}}})]
          (is (= :malformed (:rejected result)))
          (is (= {:data {:actor ["disallowed key"]}} (:because result)))))

      (testing "and the actor recorded is always the principal's"
        (intake/submit app truck-1 dana {:type :buy-flavour :data {:flavour "vanilla"}})
        (is (= {:type "user" :id "USR-83721"}
               (-> (driven/read-stream (:store app) truck-1) last :metadata :actor)))))))

(deftest malformed-input-is-rejected-before-command-identity-is-allocated-test
  (with-app
    (fn [app]
      (let [id-calls (atom 0)
            ids (reify driven/Ids
                  (new-id [_] (swap! id-calls inc) (random-uuid)))
            result (intake/submit (assoc app :ids ids) truck-1 rudi
                                  {:type :load-truck
                                   :data {:flavour "tarmac" :quantity 3}})]
        (is (= :malformed (:rejected result)))
        (is (zero? @id-calls))))))

(deftest identity-and-infrastructure-failures-are-not-business-refusals-test
  (with-app
    (fn [app]
      (let [same-id #uuid "0f1c2b3a-0000-4000-8000-000000000099"
            ids (reify driven/Ids (new-id [_] same-id))
            deps (assoc app :ids ids)]
        (intake/submit deps truck-1 rudi
                       {:type :load-truck
                        :data {:flavour "vanilla" :quantity 1}})
        (let [failure (try
                        (intake/submit deps truck-1 rudi
                                       {:type :load-truck
                                        :data {:flavour "chocolate" :quantity 1}})
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= :command-id-collision (:reason (ex-data failure)))))))))
