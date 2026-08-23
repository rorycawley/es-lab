(ns lab34.definition-test
  "The five checks, and the claim they support.

  Lab 33 refused rules-as-data because a predicate DSL has no type checker.
  This is the type checker a state machine can have, and each test below is a
  defect that would be a silently stuck instance if it were not caught at
  publication."
  (:require [clojure.test :refer [deftest is testing]]
            [lab34.definition :as definition]
            [lab34.fixture :as fixture]
            [lab34.onboarding :as onboarding]))

(deftest the-shipped-definitions-are-processes-test
  ;; So a typo in `onboarding.clj` fails here rather than in the demo.
  (is (= 3 (count (onboarding/check-all!))))
  (doseq [d [onboarding/v1 onboarding/v2 onboarding/v3]]
    (is (definition/valid? d onboarding/handled-commands))))

;; ---------------------------------------------------------------------------
;; 1. Transitions land somewhere
;; ---------------------------------------------------------------------------

(deftest a-transition-to-an-undeclared-state-is-caught-test
  ;; Note the cascade: misspelling the target also orphans everything behind
  ;; it, so the report names three problems rather than one. That is the
  ;; checks working together rather than noise — a reader who fixes the typo
  ;; watches all three go.
  (let [typo  (assoc-in onboarding/v1 [:states :awaiting-identity :on :identity-verified]
                        :awaiting-sanctionz)
        found (definition/problems typo)]
    (is (some #{":awaiting-identity on :identity-verified goes to :awaiting-sanctionz, which is not declared"}
              found))
    (is (= 3 (count found)) "the typo, and the two states it stranded")))

;; ---------------------------------------------------------------------------
;; 2. Every state is reachable
;; ---------------------------------------------------------------------------

(deftest an-unreachable-state-is-caught-test
  ;; The step somebody added and forgot to wire in. Nothing at run time would
  ;; ever report this: instances simply never visit it.
  (let [orphaned (assoc-in onboarding/v1 [:states :awaiting-documents]
                           {:on {:documents-received :approved}})]
    (is (= [":awaiting-documents is declared but unreachable from :awaiting-identity"]
           (definition/problems orphaned)))))

;; ---------------------------------------------------------------------------
;; 3. No dead ends
;; ---------------------------------------------------------------------------

(deftest a-state-with-no-way-out-is-caught-test
  ;; The one that costs real money: an instance enters, and stays forever,
  ;; and nobody notices until somebody asks why 300 applications are pending.
  (let [trap (assoc-in onboarding/v1 [:states :awaiting-sanctions :on] {})]
    (is (some #{":awaiting-sanctions is not terminal and has no way out"}
              (definition/problems trap))))
  (testing "a timeout counts as a way out"
    ;; v2's `:awaiting-manual` is the state to try this on: strip its
    ;; transitions and nothing else becomes unreachable, so the only question
    ;; left is whether waiting is an exit. It is.
    (let [waits (assoc-in onboarding/v2 [:states :awaiting-manual :on] {})]
      (is (definition/valid? waits onboarding/handled-commands)))))

;; ---------------------------------------------------------------------------
;; 4. Terminal means terminal
;; ---------------------------------------------------------------------------

(deftest a-terminal-state-that-transitions-is-caught-test
  (let [contradiction (assoc-in onboarding/v1 [:states :rejected :on]
                                {:appeal-received :awaiting-manual})]
    (is (some #(re-find #":rejected is terminal and also transitions" %)
              (definition/problems contradiction)))))

;; ---------------------------------------------------------------------------
;; 5. Somebody handles what it asks for
;; ---------------------------------------------------------------------------

(deftest a-command-nobody-handles-is-caught-test
  ;; Lab 29's derived routing table, reused. A process that asks for something
  ;; no module handles is a process with a step that silently does nothing.
  (let [misrouted (assoc-in onboarding/v1 [:states :approved :issue] :open-acount)]
    (is (= [":approved issues :open-acount, which no module handles"]
           (definition/problems misrouted onboarding/handled-commands)))
    (testing "and is invisible without the routing table"
      (is (empty? (definition/problems misrouted)))))
  (testing "a timeout's command is checked too"
    (let [misrouted (assoc-in onboarding/v1 [:states :awaiting-identity :timeout :issue]
                              :escalate-to-nobody)]
      (is (= [":awaiting-identity issues :escalate-to-nobody, which no module handles"]
             (definition/problems misrouted onboarding/handled-commands))))))

;; ---------------------------------------------------------------------------
;; Structural refusals
;; ---------------------------------------------------------------------------

(deftest a-definition-without-an-initial-state-is-not-a-process-test
  (is (= ["a definition must name an :initial state"]
         (definition/problems (dissoc onboarding/v1 :initial))))
  (is (= [":initial names :nowhere, which is not declared"]
         (definition/problems (assoc onboarding/v1 :initial :nowhere))))
  (is (= ["a definition must declare at least one state"]
         (definition/problems (assoc onboarding/v1 :states {})))))

(deftest check-refuses-and-says-why-test
  (is (= :not-a-definition
         (fixture/reason #(definition/check! (assoc onboarding/v1 :initial :nowhere)))))
  (is (= onboarding/v1 (definition/check! onboarding/v1 onboarding/handled-commands))))

(deftest problems-are-ordered-so-the-message-is-stable-test
  ;; A refusal listing its complaints in hash order is one nobody can write a
  ;; test against, including this one.
  (let [broken (-> onboarding/v1
                   (assoc-in [:states :zzz] {:on {:x :approved}})
                   (assoc-in [:states :aaa] {:on {:y :approved}}))]
    (is (= (definition/problems broken) (definition/problems broken)))
    (is (= [":aaa is declared but unreachable from :awaiting-identity"
            ":zzz is declared but unreachable from :awaiting-identity"]
           (definition/problems broken)))))

;; ---------------------------------------------------------------------------
;; The lookups
;; ---------------------------------------------------------------------------

(deftest the-runtime-cost-of-being-data-is-one-lookup-test
  (is (= :awaiting-sanctions
         (definition/next-state onboarding/v1 :awaiting-identity :identity-verified)))
  (is (nil? (definition/next-state onboarding/v1 :awaiting-identity :sanctions-cleared))
      "an event this state has no transition for is an answer, not an error")
  (is (= :open-account (definition/issued-by onboarding/v1 :approved)))
  (is (definition/terminal? onboarding/v1 :rejected))
  (is (not (definition/terminal? onboarding/v1 :awaiting-identity))))

(deftest a-timeout-is-a-duration-and-a-command-test
  (let [{:keys [after issue]} (definition/timeout-of onboarding/v1 :awaiting-identity)]
    (is (= 24 (.toHours after)))
    (is (= :escalate-review issue)))
  (is (nil? (definition/timeout-of onboarding/v1 :awaiting-sanctions))))
