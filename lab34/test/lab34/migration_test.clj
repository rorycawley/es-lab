(ns lab34.migration-test
  "Moving live instances, and what it costs to skip the question."
  (:require [clojure.test :refer [deftest is testing]]
            [lab34.fixture :as fixture]
            [lab34.instance :as instance]
            [lab34.migration :as migration]
            [lab34.onboarding :as onboarding]
            [lab34.registry :as registry]))

(def v1-and-v2 (fixture/registry-with onboarding/v1 onboarding/v2))
(def everything (fixture/registry-with onboarding/v1 onboarding/v2 onboarding/v3))

(defn- in-sanctions [id]
  (-> (instance/start onboarding/v2 id)
      (instance/observe-all (registry/resolver v1-and-v2)
                            [(fixture/event :identity-verified fixture/day-1)])))

(defn- with-a-human [id]
  (-> (instance/start onboarding/v2 id)
      (instance/observe-all (registry/resolver v1-and-v2)
                            [(fixture/event :identity-verified fixture/day-1)
                             (fixture/event :sanctions-hit fixture/day-2)])))

(deftest a-migration-moves-instances-onto-the-new-version-test
  (let [live  [(in-sanctions fixture/ada) (with-a-human fixture/grace)]
        moved (migration/migrate onboarding/v3-migration onboarding/v2 onboarding/v3 live)]
    (is (= [3 3] (mapv :definition/version moved)))
    (is (= [:awaiting-screening :awaiting-screening] (mapv instance/status moved)))
    (testing "and they carry on under v3"
      (let [done (instance/observe (first moved) (registry/resolver everything)
                                   (fixture/event :screening-cleared fixture/day-3))]
        (is (= :approved (instance/status done)))
        (is (instance/complete? done (registry/resolver everything)))))))

(deftest a-migration-that-forgets-somebody-is-refused-test
  ;; The check that makes this safe. `:awaiting-manual` has instances in it
  ;; and the mapping says nothing about them — which is not an omission to
  ;; default, it is a question nobody answered.
  (let [live      [(with-a-human fixture/grace)]
        forgetful {:awaiting-sanctions :awaiting-screening}]
    (is (= :cannot-migrate
           (fixture/reason #(migration/migrate forgetful onboarding/v2 onboarding/v3 live))))
    (is (= ["instances are in :awaiting-manual and the migration does not say where they go"]
           (fixture/problems-of
            #(migration/migrate forgetful onboarding/v2 onboarding/v3 live))))))

(deftest a-migration-to-a-state-that-does-not-exist-is-refused-test
  (let [live  [(in-sanctions fixture/ada)]
        wrong {:awaiting-sanctions :awaiting-screeening
               :awaiting-manual    :awaiting-screening}]
    (is (some #(re-find #"maps to :awaiting-screeening, which v3 does not declare" %)
              (fixture/problems-of
               #(migration/migrate wrong onboarding/v2 onboarding/v3 live))))))

(deftest a-migration-from-a-state-that-does-not-exist-is-refused-test
  ;; Usually a stale mapping left over from the previous migration.
  (let [live  [(in-sanctions fixture/ada)]
        stale (assoc onboarding/v3-migration :awaiting-documents :awaiting-screening)]
    (is (some #(re-find #":awaiting-documents is not a state in v2" %)
              (fixture/problems-of
               #(migration/migrate stale onboarding/v2 onboarding/v3 live))))))

(deftest a-state-that-survives-needs-no-mapping-test
  ;; Only states that disappear need instructions. `:approved` and `:rejected`
  ;; exist in both, so instances in them are carried across untouched.
  (let [done (-> (instance/start onboarding/v2 fixture/alan)
                 (instance/observe-all (registry/resolver v1-and-v2)
                                       [(fixture/event :identity-rejected fixture/day-1)]))
        [moved] (migration/migrate {} onboarding/v2 onboarding/v3 [done])]
    (is (= :rejected (instance/status moved)))
    (is (= 3 (:definition/version moved)))))

(deftest the-recorded-path-is-not-rewritten-test
  ;; The instance went through `:awaiting-sanctions`. It did. v3 has never
  ;; heard of that state, and rewriting the history to look like the new
  ;; process would be falsifying the record for tidiness.
  (let [live    (with-a-human fixture/grace)
        [moved] (migration/migrate onboarding/v3-migration onboarding/v2 onboarding/v3 [live])]
    (is (= [:awaiting-sanctions :awaiting-manual] (mapv :to (get-in moved [:state :history])))
        "states v3 does not declare, still recorded")
    (testing "and the move is appended as its own fact"
      (is (migration/migrated? moved))
      (is (= [{:from-version 2 :to-version 3
               :from-status :awaiting-manual :to-status :awaiting-screening}]
             (get-in moved [:state :migrations]))))))

(deftest migrating-twice-is-visible-test
  (let [live    (in-sanctions fixture/ada)
        [once]  (migration/migrate onboarding/v3-migration onboarding/v2 onboarding/v3 [live])
        [twice] (migration/migrate {} onboarding/v3 onboarding/v3 [once])]
    (is (= 2 (count (get-in twice [:state :migrations]))))))

(deftest an-unmigrated-instance-cannot-fold-under-the-new-version-test
  ;; What migration exists to prevent, forced by hand: reassigning the version
  ;; without moving the state.
  (let [pretend (assoc (in-sanctions fixture/ada) :definition/version 3)]
    (is (= :state-not-in-definition
           (fixture/reason #(instance/observe pretend (registry/resolver everything)
                                              (fixture/event :screening-cleared fixture/day-3)))))))
