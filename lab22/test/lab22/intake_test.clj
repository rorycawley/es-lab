(ns lab22.intake-test
  "The driving edge: two ways to say no, and why they are two."
  (:require [clojure.test :refer [deftest is testing]]
            [lab22.adapter.clock :as clock]
            [lab22.adapter.intake :as intake]
            [lab22.app :as app]
            [lab22.port :as port]
            [lab22.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- with-app [f]
  (let [sys (system/start (system/in-memory {:clock (clock/fixed-clock t0)}))]
    (try (f (system/app sys)) (finally (system/stop sys)))))

(deftest a-well-formed-message-becomes-events-test
  (with-app
    (fn [app]
      (let [result (intake/submit app truck-1 {:type :load-truck
                                               :data {:flavour "vanilla" :quantity 3}})]
        (is (= [:truck-loaded] (map :event/type (:accepted result))))
        (is (= {"vanilla" 3} (app/stock app truck-1)))))))

(deftest malformed-and-refused-are-different-answers-test
  (with-app
    (fn [app]
      (testing "malformed: never reaches the domain, nothing is recorded"
        (let [result (intake/submit app truck-1 {:type :load-truck
                                                 :data {:flavour "tarmac" :quantity 3}})]
          (is (= :malformed (:rejected result)))
          (is (some? (:because result)) "and it says what was wrong")
          (is (empty? (port/read-stream (:store app) truck-1)))))

      (testing "refused: well-formed, reached the domain, and the domain said no"
        (let [result (intake/submit app truck-1 {:type :buy-flavour
                                                 :data {:flavour "vanilla"}})]
          (is (= :refused (:rejected result)))
          (is (= "Sold out" (:because result)))
          (is (empty? (port/read-stream (:store app) truck-1)))))

      (testing "both record nothing — but for entirely different reasons"
        (is (not= :malformed :refused))))))

(deftest the-same-message-flips-from-refused-to-accepted-test
  (with-app
    (fn [app]
      (let [buy {:type :buy-flavour :data {:flavour "vanilla"}}]
        (is (= :refused (:rejected (intake/submit app truck-1 buy))))
        (intake/submit app truck-1 {:type :load-truck :data {:flavour "vanilla" :quantity 1}})
        (is (some? (:accepted (intake/submit app truck-1 buy)))
            "state changed, so the domain's answer changed")))))

(deftest a-malformed-message-never-flips-test
  (with-app
    (fn [app]
      (let [bad {:type :buy-flavour :data {:flavour "tarmac"}}]
        (is (= :malformed (:rejected (intake/submit app truck-1 bad))))
        (intake/submit app truck-1 {:type :load-truck :data {:flavour "vanilla" :quantity 9}})
        (is (= :malformed (:rejected (intake/submit app truck-1 bad)))
            "no amount of state makes tarmac a flavour")))))

(deftest an-unknown-type-stops-at-the-door-test
  (with-app
    (fn [app]
      (is (= :malformed (:rejected (intake/submit app truck-1 {:type :steal-truck :data {}})))))))

(deftest malformed-input-is-rejected-before-a-command-is-constructed-test
  (with-app
    (fn [app]
      (let [id-calls (atom 0)
            ids      (reify port/Ids
                       (new-id [_]
                         (swap! id-calls inc)
                         (random-uuid)))
            result   (intake/submit (assoc app :ids ids)
                                    truck-1
                                    {:type :load-truck
                                     :data {:flavour "tarmac" :quantity 3}})]
        (is (= :malformed (:rejected result)))
        (is (zero? @id-calls)
            "validation happens before command/id allocation")))))

(deftest infrastructure-and-identity-failures-are-not-labelled-business-refusals-test
  (with-app
    (fn [app]
      (let [same-id #uuid "0f1c2b3a-0000-4000-8000-000000000099"
            ids (reify port/Ids (new-id [_] same-id))
            deps (assoc app :ids ids)]
        (intake/submit deps truck-1
                       {:type :load-truck
                        :data {:flavour "vanilla" :quantity 1}})
        (let [failure (try
                        (intake/submit deps truck-1
                                       {:type :load-truck
                                        :data {:flavour "chocolate" :quantity 1}})
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= :command-id-collision (:reason (ex-data failure)))))))))
