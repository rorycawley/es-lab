(ns lab34.instance
  "One run of a process, and the version it is running under.

  This namespace is three fields and it is the whole lab.

      {:process/id      #uuid \"…\"
       :process/name    :onboarding
       :definition/version 1        ; <- pinned at start, never reassigned
       :state           {…}}

  Lab 33's rule was that a parameter a decision used must be recorded on the
  event, so the decision stays reproducible when the parameter moves. A
  process instance uses a whole state machine, so it records which one.

  What that buys is the thing a configuration file cannot give you: the
  question *which definition is this instance following?* has an answer, and
  the answer does not change when somebody publishes a new version."
  (:require [lab34.definition :as definition]
            [lab34.process :as process]))

(defn start
  "Begin an instance under a specific definition, and remember which.

  The version is taken from the definition rather than passed separately,
  because two arguments that must agree are one argument with a bug waiting
  in it."
  [definition process-id]
  {:process/id         process-id
   :process/name       (:process/name definition)
   :definition/version (definition/version-of definition)
   :state              (process/initial-state definition)})

(defn observe
  "Fold one event into the instance, under the definition it is pinned to.

  `resolve-definition` is a function from a version to that definition — a
  registry, usually. The instance says which version it needs; the caller
  supplies it. Note the direction: nothing here asks what the *current*
  version is, and there is no argument by which it could."
  [instance resolve-definition event]
  (let [definition (resolve-definition (:definition/version instance))]
    (update instance :state #(process/evolve definition % event))))

(defn observe-all
  [instance resolve-definition events]
  (reduce (fn [i event] (observe i resolve-definition event)) instance events))

(defn decide
  [instance resolve-definition now]
  (let [definition (resolve-definition (:definition/version instance))]
    (process/decide definition (:state instance) (:process/id instance) now)))

(defn complete?
  [instance resolve-definition]
  (process/complete? (resolve-definition (:definition/version instance))
                     (:state instance)))

(defn status [instance] (get-in instance [:state :status]))

(defn in-flight?
  [instance resolve-definition]
  (not (complete? instance resolve-definition)))
