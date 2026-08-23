(ns lab33.rules
  "Configuration, and the check that stops it becoming a programming language.

  Every parameter this system will accept is named below, with what its value
  must be. The map *is* the schema, and it is closed: an unknown key is
  refused, and so is a value of the wrong shape.

  That closedness is the whole mechanism, and it is worth being precise about
  what it buys. It is not input validation -- nobody untrusted edits this. It
  is the line between **values** and **structure**:

      {:reporting-threshold 15000M}                 a value.    Accepted.
      {:flag-when [:and [:> :amount 10000] …]}      structure.  Refused.

  The second is a rule expressed as data, which means an interpreter has to
  exist to run it -- and an interpreter is a language with no type checker, no
  tests, no code review and no `git blame`. `engine/predicate.clj` builds one,
  small and honest, so the cost is demonstrable rather than asserted. This
  namespace is what keeps it out.

  Fifteen lines of `clojure.core` and no library, deliberately. A schema
  library here would work and would hide the rule behind an abstraction, when
  the point is that a reader can check it in one sitting."
  (:require [clojure.string :as str]))

(def parameters
  "The complete set of configurable parameters, and the predicate each value
  must satisfy.

  Adding a key here is a deliberate act with a code review attached, which is
  the intended friction. Note that every predicate tests one scalar: none of
  them accepts a collection, so no parameter can smuggle in a nested rule.

  The values are **vars** rather than functions, which costs nothing -- a var
  is callable, so `(pred v)` still works -- and buys a readable failure. A
  function value has no name at runtime; `#'decimal?` carries `decimal?` in
  its metadata, so the refusal can say what was expected instead of only what
  was wrong."
  {:reporting-threshold #'decimal?
   :overdraft-limit     #'decimal?
   :withdrawal-fee      #'decimal?
   :review-above        #'decimal?
   :sweep-amount        #'decimal?})

(defn- expected
  "The name of the predicate a parameter's value must satisfy."
  [pred]
  (name (:name (meta pred))))

(def defaults
  "What lab 32 hard-coded, now named.

  `10000M` is the reporting threshold from lab 32's `projections.clj`, which
  is where this lab starts: it was a `def` in the middle of a projection, and
  the question that produced this lab was what happens when a regulator moves
  it."
  {:reporting-threshold 10000M
   :overdraft-limit     0M
   :withdrawal-fee      0M
   :review-above        50000M
   :sweep-amount        100M})

(defn problems
  "Why this configuration is not acceptable, as a vector of readable strings.

  Returns an empty vector for acceptable configuration, so the caller can use
  `seq` and does not have to distinguish nil from empty."
  [config]
  (cond
    (not (map? config))
    ["configuration must be a map"]

    :else
    (into []
          (concat
           (for [k (sort-by str (keys config))
                 :when (not (contains? parameters k))]
             (str k " is not a configurable parameter"))
           (for [[k v] (sort-by (comp str key) config)
                 :let  [acceptable (get parameters k)]
                 :when (and acceptable (not (acceptable v)))]
             (str k " must satisfy " (expected acceptable) ", got " (pr-str v)))))))

(defn valid?
  [config]
  (empty? (problems config)))

(defn check!
  "Return `config`, or throw explaining why it is not configuration.

  Thrown rather than returned because there is no sensible partial answer: a
  system that starts with configuration it does not understand is a system
  whose behaviour nobody can predict, and the only moment anybody is
  definitely looking is start-up."
  [config]
  (let [found (problems config)]
    (when (seq found)
      (throw (ex-info (str "Not configuration: " (str/join "; " found))
                      {:reason :not-configuration :problems found})))
    config))

(defn configure
  "`defaults`, with `overrides` applied and the result checked.

  A flat merge and never a deep one. Deep-merging configuration is how a
  nested map -- which is to say, structure -- gets in without anybody
  deciding to allow it."
  ([] (configure {}))
  ([overrides]
   (check! overrides)
   (merge defaults overrides)))

(defn parameter
  "Read one parameter. Every read goes through here, so a parameter that was
  never declared cannot be read by a typo either."
  [config k]
  (when-not (contains? parameters k)
    (throw (ex-info "No such parameter" {:reason :unknown-parameter :parameter k})))
  (get config k))
