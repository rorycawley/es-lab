(ns lab34.fixture
  "Fixed values. No clock, no minted identifiers — see lab 33 for why a lab
  about answers changing cannot afford a second reason for them to change."
  (:require [lab34.onboarding :as onboarding]
            [lab34.registry :as registry])
  (:import (java.time Instant)))

(defn instant ^Instant [s] (Instant/parse s))

(def day-1  (instant "2026-03-01T09:00:00Z"))
(def day-1b (instant "2026-03-01T15:00:00Z"))
(def day-2  (instant "2026-03-02T09:00:00Z"))
(def day-3  (instant "2026-03-03T09:00:00Z"))
(def day-30 (instant "2026-03-30T09:00:00Z"))

(def ada  #uuid "00000000-0000-4000-8000-0000000000a1")
(def grace #uuid "00000000-0000-4000-8000-0000000000a2")
(def alan #uuid "00000000-0000-4000-8000-0000000000a3")

(defn event
  ([type at] (event type at #uuid "00000000-0000-4000-8000-0000000000e1"))
  ([type at id] {:event/id id :event/type type :occurred-at at :data {}}))

(defn registry-with
  "A registry holding the given versions, published in order."
  [& definitions]
  (reduce (fn [r d] (registry/publish r d [] onboarding/handled-commands))
          (registry/registry :onboarding)
          definitions))

(defn reason
  "The `:reason` of a refusal, or `:no-refusal`."
  [f]
  (try
    (f)
    :no-refusal
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e) :no-reason))))

(defn problems-of
  "The `:problems` of a refusal, for asserting on the explanation rather than
  only on the fact of it."
  [f]
  (try
    (f)
    []
    (catch clojure.lang.ExceptionInfo e
      (vec (:problems (ex-data e))))))
