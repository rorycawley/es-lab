(ns lab34.contrast-test
  "What the hardcoded version cannot do — and what it does perfectly well.

  Lab 0's `contrast_test` shape. The counter-example is not a straw man: for a
  process that never changes it is the better choice, and this file starts by
  showing it works."
  (:require [clojure.test :refer [deftest is testing]]
            [lab34.definition :as definition]
            [lab34.engine.hardcoded :as hardcoded]
            [lab34.fixture :as fixture]
            [lab34.instance :as instance]
            [lab34.onboarding :as onboarding]
            [lab34.process :as process]
            [lab34.registry :as registry]))

(def registry-v2 (fixture/registry-with onboarding/v1 onboarding/v2))

(deftest the-hardcoded-version-is-correct-test
  ;; Same events, same answers as v2. Shorter, readable, and for a fixed
  ;; process the right thing to write.
  (let [events [(fixture/event :identity-verified fixture/day-1)
                (fixture/event :sanctions-hit fixture/day-2)
                (fixture/event :manually-approved fixture/day-3)]
        by-hand (reduce hardcoded/advance :awaiting-identity events)
        by-data (:status (process/replay onboarding/v2 events))]
    (is (= :approved by-hand))
    (is (= by-hand by-data))))

;; ---------------------------------------------------------------------------
;; The three questions
;; ---------------------------------------------------------------------------

(deftest it-cannot-say-what-version-an-instance-is-running-test
  ;; There is no version. Every instance is running whatever is deployed, and
  ;; "which process did this application go through?" has no answer six months
  ;; later when the code has moved on.
  (let [pinned (instance/start onboarding/v2 fixture/ada)]
    (is (= 2 (:definition/version pinned)))
    (is (nil? (resolve 'lab34.engine.hardcoded/version))
        "the hardcoded process has no version to pin to")))

(deftest it-cannot-enumerate-its-own-steps-test
  ;; The definition can be asked what it contains. A `case` can be read by a
  ;; person and not by a program, so nothing can check it, diagram it, or
  ;; diff two releases of it.
  (is (= #{:awaiting-identity :awaiting-sanctions :awaiting-manual :approved :rejected}
         (definition/states onboarding/v2)))
  (testing "and the hardcoded one offers no equivalent"
    (is (nil? (resolve 'lab34.engine.hardcoded/states)))))

(deftest it-cannot-be-checked-before-it-runs-test
  ;; The heart of it. A misrouted command in a definition is caught at
  ;; publication; the same mistake in a `case` compiles, deploys, and is
  ;; discovered when a step silently does nothing.
  (let [misrouted (assoc-in onboarding/v2 [:states :approved :issue] :open-acount)]
    (is (= [":approved issues :open-acount, which no module handles"]
           (definition/problems misrouted onboarding/handled-commands)))
    (is (= :cannot-publish
           (fixture/reason #(registry/publish registry-v2
                                              (assoc misrouted :process/version 9)
                                              [] onboarding/handled-commands))))))

(deftest changing-it-changes-every-running-instance-at-once-test
  ;; The consequence the whole lab is about, shown from the other side.
  ;;
  ;; With the process as data, an instance pinned to v1 keeps folding under v1
  ;; after v2 ships. With the process as code there is nothing to pin to: the
  ;; deployed function is the only one there is, so a running instance that
  ;; got a sanctions hit yesterday would have been rejected, and the same
  ;; instance getting one today goes to a human. Nothing recorded the change.
  (let [hit    (fixture/event :sanctions-hit fixture/day-2)
        before (hardcoded/advance :awaiting-sanctions hit)
        pinned (-> (instance/start onboarding/v1 fixture/ada)
                   (instance/observe-all (registry/resolver registry-v2)
                                         [(fixture/event :identity-verified fixture/day-1)])
                   (instance/observe (registry/resolver registry-v2) hit))]
    (is (= :awaiting-manual before)
        "the deployed code is v2's behaviour, and it applies to everybody")
    (is (= :rejected (instance/status pinned))
        "while the pinned instance still follows the process it started under")))

(deftest and-nothing-can-ask-what-is-in-flight-test
  ;; The operational question. The registry answers it because instances carry
  ;; a version; with hardcoded logic there is no grouping to make.
  (let [ada   (-> (instance/start onboarding/v1 fixture/ada)
                  (instance/observe-all (registry/resolver registry-v2)
                                        [(fixture/event :identity-verified fixture/day-1)]))
        grace (-> (instance/start onboarding/v2 fixture/grace)
                  (instance/observe-all (registry/resolver registry-v2)
                                        [(fixture/event :identity-verified fixture/day-1)]))]
    (is (= {1 1 2 1} (update-vals (registry/in-flight registry-v2 [ada grace]) count)))))
