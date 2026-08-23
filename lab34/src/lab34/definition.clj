(ns lab34.definition
  "A process, as data — and the checks that earn it the right to be data.

  Lab 33 refused rules expressed as data, because a predicate needs an
  interpreter and an interpreter is a language with no type checker:
  `:ammount` is a valid program that silently matches nothing forever.

  A state machine is the exception, and the reason is narrow rather than a
  matter of taste. It needs no interpreter — only a lookup — and every way of
  getting one wrong is **decidable before it runs**:

      every :next names a declared state       or a transition goes nowhere
      every declared state is reachable        or a step can never be entered
      every non-terminal state has an exit     or an instance is stuck forever
      every terminal state has no transitions  or it is not terminal
      every :issue is a command somebody handles

  Five total checks, no execution required. That is the guarantee lab 33's
  predicate DSL could not have, and it is the whole of the argument for
  letting this be a map instead of a `defmulti`.

  Note what stays in code: guards. `:on` is a lookup from event type to next
  state, and there is deliberately no `:when` clause anywhere in this shape. A
  guard is a predicate, and lab 33 settled predicates."
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.time Duration)))

;; ---------------------------------------------------------------------------
;; The shape
;;
;;   {:process/name    :onboarding
;;    :process/version 1
;;    :initial         :awaiting-identity
;;    :states
;;    {:awaiting-identity {:on      {:identity-verified :awaiting-sanctions
;;                                   :identity-rejected :rejected}
;;                         :timeout {:after "PT24H" :issue :escalate-review}}
;;     :approved          {:issue :open-account :terminal true}
;;     :rejected          {:terminal true}}}
;; ---------------------------------------------------------------------------

(defn states     [definition] (set (keys (:states definition))))
(defn terminal?  [definition state] (boolean (get-in definition [:states state :terminal])))
(defn transitions [definition state] (get-in definition [:states state :on] {}))

(defn next-state
  "Where `event-type` takes an instance sitting in `state`, or nil.

  A lookup, and nothing else. This one function is the entire runtime cost of
  the definition being data."
  [definition state event-type]
  (get (transitions definition state) event-type))

(defn issued-by
  "The command a state asks for on entry, or nil."
  [definition state]
  (get-in definition [:states state :issue]))

(defn timeout-of
  [definition state]
  (when-let [{:keys [after issue]} (get-in definition [:states state :timeout])]
    {:after (Duration/parse after) :issue issue}))

(defn version-of [definition] (:process/version definition))

;; ---------------------------------------------------------------------------
;; The checks
;; ---------------------------------------------------------------------------

(defn- reachable
  "Every state reachable from `:initial`, by breadth-first walk.

  Pure graph traversal over the declared transitions. There is no execution
  here and no instance — which is exactly why this can be answered before
  anything runs."
  [definition]
  (loop [seen #{} queue [(:initial definition)]]
    (if-let [state (first queue)]
      (if (seen state)
        (recur seen (rest queue))
        (recur (conj seen state)
               (into (vec (rest queue)) (vals (transitions definition state)))))
      seen)))

(defn problems
  "Why this definition is not a process, as a vector of readable strings.

  `handled-commands` is the set of command types some module has declared it
  handles — lab 29's derived routing table, reused. Passing it in rather than
  reaching for a registry keeps this a pure function of its arguments, and
  lets a caller who does not care omit it."
  ([definition] (problems definition nil))
  ([definition handled-commands]
   (let [declared (states definition)
         initial  (:initial definition)
         live     (reachable definition)]
     (cond
       (not (map? definition))     ["a definition must be a map"]
       (empty? declared)           ["a definition must declare at least one state"]
       (nil? initial)              ["a definition must name an :initial state"]
       (not (declared initial))    [(str ":initial names " initial ", which is not declared")]

       :else
       (into []
             (concat
              ;; 1. transitions land somewhere
              (for [state (sort-by str declared)
                    [event target] (sort-by (comp str key) (transitions definition state))
                    :when (not (declared target))]
                (str state " on " event " goes to " target ", which is not declared"))

              ;; 2. every state is reachable
              (for [state (sort-by str (set/difference declared live))]
                (str state " is declared but unreachable from " initial))

              ;; 3. no state is a dead end
              (for [state (sort-by str declared)
                    :when (and (not (terminal? definition state))
                               (empty? (transitions definition state))
                               (not (timeout-of definition state)))]
                (str state " is not terminal and has no way out"))

              ;; 4. terminal means terminal
              (for [state (sort-by str declared)
                    :when (and (terminal? definition state)
                               (seq (transitions definition state)))]
                (str state " is terminal and also transitions"))

              ;; 5. somebody handles what it asks for
              (when handled-commands
                (for [state (sort-by str declared)
                      :let  [asks (remove nil? [(issued-by definition state)
                                                (:issue (timeout-of definition state))])]
                      command asks
                      :when (not (contains? handled-commands command))]
                  (str state " issues " command ", which no module handles")))))))))

(defn valid?
  ([definition] (valid? definition nil))
  ([definition handled-commands] (empty? (problems definition handled-commands))))

(defn check!
  "Return the definition, or throw explaining why it is not one.

  Thrown at publication rather than reported at run time, which is the point
  of the whole exercise: an incomplete process should be impossible to install,
  not something an instance discovers by getting stuck in it."
  ([definition] (check! definition nil))
  ([definition handled-commands]
   (let [found (problems definition handled-commands)]
     (when (seq found)
       (throw (ex-info (str "Not a process definition: " (str/join "; " found))
                       {:reason :not-a-definition :problems found})))
     definition)))
