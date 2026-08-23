(ns lab34.pinning-test
  "The lab.

  An instance folds under the definition it started with. Publishing a new
  version cannot move it, cannot re-state it, and cannot change what it asks
  for — which is lab 33's rule about folds and configuration, applied to a
  whole state machine instead of a scalar."
  (:require [clojure.test :refer [deftest is testing]]
            [lab34.fixture :as fixture]
            [lab34.instance :as instance]
            [lab34.onboarding :as onboarding]
            [lab34.registry :as registry]))

(def only-v1 (fixture/registry-with onboarding/v1))
(def v1-and-v2 (fixture/registry-with onboarding/v1 onboarding/v2))

(defn- started-under [definition registry]
  (-> (instance/start definition fixture/ada)
      (instance/observe-all (registry/resolver registry)
                            [(fixture/event :identity-verified fixture/day-1)])))

;; ---------------------------------------------------------------------------
;; The claim
;; ---------------------------------------------------------------------------

(deftest an-instance-folds-under-the-version-it-started-with-test
  ;; The same events, the same instance, and a newer definition available in
  ;; the registry. Under v1 a sanctions hit rejects; under v2 it goes to a
  ;; human. This instance started under v1, so it rejects — and it would still
  ;; reject if v2 had been published a moment after it started.
  (let [pinned (started-under onboarding/v1 v1-and-v2)
        after  (instance/observe pinned (registry/resolver v1-and-v2)
                                 (fixture/event :sanctions-hit fixture/day-2))]
    (is (= 1 (:definition/version after)))
    (is (= :rejected (instance/status after))
        "v2 would have sent this to :awaiting-manual, and v2 is published")))

(deftest a-new-instance-gets-the-new-version-test
  ;; The other half. Pinning is not freezing: instances started after the
  ;; publication use the new definition.
  (let [fresh (-> (instance/start onboarding/v2 fixture/grace)
                  (instance/observe-all (registry/resolver v1-and-v2)
                                        [(fixture/event :identity-verified fixture/day-1)
                                         (fixture/event :sanctions-hit fixture/day-2)]))]
    (is (= 2 (:definition/version fresh)))
    (is (= :awaiting-manual (instance/status fresh)))))

(deftest two-versions-run-at-once-and-that-is-correct-test
  ;; The consequence people find uncomfortable, stated plainly. It is not a
  ;; smell — it is what it means for a process to take longer than the
  ;; interval between releases.
  (let [old (started-under onboarding/v1 v1-and-v2)
        new (-> (instance/start onboarding/v2 fixture/grace)
                (instance/observe-all (registry/resolver v1-and-v2)
                                      [(fixture/event :identity-verified fixture/day-1)]))
        hit (fixture/event :sanctions-hit fixture/day-2)
        r   (registry/resolver v1-and-v2)]
    (is (= :rejected (instance/status (instance/observe old r hit))))
    (is (= :awaiting-manual (instance/status (instance/observe new r hit))))
    (testing "and the registry can say what is running under what"
      (is (= {1 1 2 1} (update-vals (registry/in-flight v1-and-v2 [old new]) count))))))

;; ---------------------------------------------------------------------------
;; Why it has to be this way
;; ---------------------------------------------------------------------------

(deftest folding-under-the-wrong-definition-is-refused-not-guessed-test
  ;; The failure the pin prevents, forced by hand. An instance sitting in
  ;; :awaiting-sanctions folded under v3 — which has no such state — is not a
  ;; question with a sensible default answer.
  (let [stuck (assoc (started-under onboarding/v1 v1-and-v2)
                     :definition/version 3)
        all   (fixture/registry-with onboarding/v1 onboarding/v2 onboarding/v3)]
    (is (= :state-not-in-definition
           (fixture/reason #(instance/observe stuck (registry/resolver all)
                                              (fixture/event :sanctions-cleared fixture/day-2)))))))

(deftest the-fold-cannot-ask-what-the-current-version-is-test
  ;; Structural rather than behavioural. `instance/observe` is handed a
  ;; resolver — a function from version to definition — and not the registry,
  ;; so there is no argument by which it could look up the latest. The
  ;; narrowest capability that does the job is the one that cannot be misused.
  (let [resolver (registry/resolver v1-and-v2)]
    (is (= onboarding/v1 (resolver 1)))
    (is (= onboarding/v2 (resolver 2)))
    (is (= :unknown-version (fixture/reason #(resolver 99))))))

(deftest replaying-an-instance-twice-gives-the-same-answer-test
  ;; Determinism, which is what pinning buys and what makes every other
  ;; assertion in this suite meaningful.
  (let [events [(fixture/event :identity-verified fixture/day-1)
                (fixture/event :sanctions-hit fixture/day-2)]
        run    #(instance/observe-all (instance/start onboarding/v1 fixture/ada)
                                      (registry/resolver v1-and-v2) events)]
    (is (= (run) (run)))))

(deftest the-recorded-path-says-which-states-it-actually-went-through-test
  ;; An instance carries where it has been, not merely where it is. That is
  ;; what lets a migrated instance's history contain a state its current
  ;; definition has never heard of without anything being inconsistent.
  (let [done (-> (instance/start onboarding/v1 fixture/ada)
                 (instance/observe-all (registry/resolver only-v1)
                                       [(fixture/event :identity-verified fixture/day-1)
                                        (fixture/event :sanctions-cleared fixture/day-2)]))]
    (is (= [:awaiting-sanctions :approved] (mapv :to (get-in done [:state :history]))))
    (is (= [:awaiting-identity :awaiting-sanctions] (mapv :from (get-in done [:state :history]))))
    (is (instance/complete? done (registry/resolver only-v1)))))
