(ns lab34.process-test
  "The Decider itself: the fold, the decision, and time as an argument."
  (:require [clojure.test :refer [deftest is]]
            [lab34.fixture :as fixture]
            [lab34.onboarding :as onboarding]
            [lab34.process :as process]))

(def v1 onboarding/v1)
(def v2 onboarding/v2)

(defn- after [definition & events]
  (process/replay definition events))

;; ---------------------------------------------------------------------------
;; evolve
;; ---------------------------------------------------------------------------

(deftest the-fold-starts-where-the-definition-says-test
  (is (= :awaiting-identity (:status (process/initial-state v1))))
  (is (= :awaiting-screening (:status (process/initial-state onboarding/v3)))))

(deftest an-event-with-no-transition-is-ignored-not-refused-test
  ;; A process manager observes a stream it does not own, so most of what it
  ;; sees is somebody else's business. Lab 11 threw on an unknown event type
  ;; because its transitions were code and a missing method meant a missing
  ;; decision. Here the transitions are data and the definition is checked as
  ;; a whole, so "no transition from here on that" is an answer.
  (let [state (after v1 (fixture/event :money-deposited fixture/day-1))]
    (is (= :awaiting-identity (:status state)))
    (is (empty? (:history state)))))

(deftest an-unknown-state-is-refused-test
  ;; The one thing not tolerated, because it means the instance is folding
  ;; under a definition that does not describe it.
  (is (= :state-not-in-definition
         (fixture/reason #(process/evolve v1 {:status :awaiting-screening}
                                          (fixture/event :screening-cleared fixture/day-1))))))

(deftest the-same-events-fold-differently-under-different-definitions-test
  ;; The reason an instance must pin. This is not a bug being demonstrated —
  ;; it is the correct behaviour of two different processes, and precisely why
  ;; "which one am I following?" has to have an answer.
  (let [events [(fixture/event :identity-verified fixture/day-1)
                (fixture/event :sanctions-hit fixture/day-2)]]
    (is (= :rejected (:status (process/replay v1 events))))
    (is (= :awaiting-manual (:status (process/replay v2 events))))))

(deftest entering-a-state-records-when-test
  ;; Which is what makes a timeout answerable without a clock.
  (let [state (after v1 (fixture/event :identity-verified fixture/day-1))]
    (is (= fixture/day-1 (:entered-at state)))))

;; ---------------------------------------------------------------------------
;; decide
;; ---------------------------------------------------------------------------

(deftest a-state-asks-for-what-the-definition-says-on-entry-test
  (let [approved (after v1
                        (fixture/event :identity-verified fixture/day-1)
                        (fixture/event :sanctions-cleared fixture/day-2))
        [command] (process/decide v1 approved fixture/ada fixture/day-2)]
    (is (= :open-account (:command/type command)))
    (is (= :approved (get-in command [:data :state])))
    (is (= :entered (get-in command [:data :because])))))

(deftest a-state-that-asks-for-nothing-asks-for-nothing-test
  (is (empty? (process/decide v1 (process/initial-state v1) fixture/ada fixture/day-1))))

(deftest a-timeout-fires-only-once-the-time-has-passed-test
  ;; Time is an argument, per lab 11. Nothing here reads a clock, so the same
  ;; question asked twice gives the same answer forever.
  (let [waiting (assoc (process/initial-state v1) :entered-at fixture/day-1)
        asked   #(process/decide v1 waiting fixture/ada %)]
    (is (empty? (asked fixture/day-1b)) "six hours in, the 24-hour window is open")
    (is (= [:escalate-review] (mapv :command/type (asked fixture/day-2))))
    (is (= :timed-out (get-in (first (asked fixture/day-2)) [:data :because])))))

(deftest a-state-with-no-timeout-never-times-out-test
  (let [sanctions (after v1 (fixture/event :identity-verified fixture/day-1))]
    (is (empty? (process/decide v1 sanctions fixture/ada fixture/day-30)))))

(deftest a-timeout-duration-is-configuration-and-reaches-a-live-instance-test
  ;; Lab 33's question, asked of a value rather than a shape. v2 gives
  ;; `:awaiting-manual` seven days; shorten it and an instance that entered
  ;; six days ago is suddenly overdue. Same instance, same events, different
  ;; answer — which is why the duration is pinned along with everything else.
  (let [entered  (assoc (process/initial-state v2) :status :awaiting-manual
                        :entered-at fixture/day-1)
        impatient (assoc-in v2 [:states :awaiting-manual :timeout :after] "PT1H")]
    (is (empty? (process/decide v2 entered fixture/ada fixture/day-3)))
    (is (= [:abandon-application]
           (mapv :command/type (process/decide impatient entered fixture/ada fixture/day-3))))))

;; ---------------------------------------------------------------------------
;; Command identity
;; ---------------------------------------------------------------------------

(deftest the-command-id-is-stable-across-definition-versions-test
  ;; Lab 33's trap. Fold the version into the derivation and a migrated
  ;; instance re-issues a command it already issued — because the id no longer
  ;; matches the one the target already deduplicated against.
  (let [approved {:status :approved :entered-at fixture/day-2}
        under-v1 (first (process/decide v1 approved fixture/ada fixture/day-2))
        under-v3 (first (process/decide onboarding/v3 approved fixture/ada fixture/day-2))]
    (is (= (:command/id under-v1) (:command/id under-v3)))))

(deftest entry-and-timeout-commands-have-different-ids-test
  ;; One state can ask for two things, and they are two requests. Sharing an
  ;; id would make the escalation deduplicate against the entry command and
  ;; silently never happen.
  (let [both (-> v1
                 (assoc-in [:states :awaiting-identity :issue] :request-documents))
        state (assoc (process/initial-state both) :entered-at fixture/day-1)
        asked (process/decide both state fixture/ada fixture/day-2)]
    (is (= [:request-documents :escalate-review] (mapv :command/type asked)))
    (is (= 2 (count (distinct (map :command/id asked)))))))

(deftest deciding-twice-asks-for-the-same-thing-test
  (let [approved {:status :approved :entered-at fixture/day-2}]
    (is (= (process/decide v1 approved fixture/ada fixture/day-2)
           (process/decide v1 approved fixture/ada fixture/day-2)))))
