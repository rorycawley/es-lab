(ns lab33.rules.stream
  "The move worth considering: configuration as an event stream of its own.

  A threshold change is a business fact. Somebody decided it, at a time, for a
  reason, and it takes effect from a date that is usually not the date it was
  entered. Every one of those is a field, and a file on disk has nowhere to
  put any of them.

  Model it as a stream and four problems stop being problems:

    as-of      the parameter in force on any past date is a fold prefix
    audit      who changed it and why is on the fact, not in a commit message
    stamping   a decision can record a version instead of copying values
    rebuild    the 'configuration' is just a projection, so lab 9 applies

  Note what this namespace is: an `evolve` over a stream of parameter changes.
  It is the same fold lab 6 introduced, applied to the rules themselves — and
  it reads no configuration, because it *is* the configuration. That is not a
  paradox, it is the recursion terminating.

  Time is an argument here and never `Instant/now`, for lab 11's reason and
  this lab's: a function that asks the clock cannot be replayed to the same
  answer twice."
  (:require [lab33.rules :as rules])
  (:import (java.time Instant)))

(defn changed
  "A parameter change, as a fact."
  [parameter value ^Instant effective-from by reason]
  (when-not (contains? rules/parameters parameter)
    (throw (ex-info "No such parameter" {:reason :unknown-parameter
                                         :parameter parameter})))
  (rules/check! {parameter value})
  {:event/type :parameter-changed
   :data       {:parameter      parameter
                :value          value
                :effective-from effective-from
                :changed-by     by
                :reason         reason}})

(defn- in-force?
  [^Instant instant {:keys [data]}]
  (let [^Instant from (:effective-from data)]
    (not (.isAfter from instant))))

(defn as-of
  "The configuration in force at `instant`.

  Ordered by effective date rather than by position in the stream, because the
  two genuinely differ: a change entered on Friday may take effect from the
  first of the month, and the answer to *what was the threshold in March* is
  not *what did we know in March*. That is lab 18's two axes, and this is the
  smallest system in which they diverge.

  Later effective dates win. Two changes to one parameter effective at the
  same instant would be a modelling error rather than a tie to break, so the
  stream order decides and the last one entered stands."
  [events ^Instant instant]
  (reduce (fn [config {:keys [data]}]
            (assoc config (:parameter data) (:value data)))
          rules/defaults
          (->> events
               (filter #(= :parameter-changed (:event/type %)))
               (filter (partial in-force? instant))
               (sort-by (comp :effective-from :data)))))

(defn current
  "Shorthand for `as-of` at the latest effective date in the stream.

  Deliberately not 'now'. The caller who wants now passes now."
  [events]
  (if-let [latest (->> events
                       (filter #(= :parameter-changed (:event/type %)))
                       (map (comp :effective-from :data))
                       (sort)
                       (last))]
    (as-of events latest)
    rules/defaults))

(defn history
  "Every value a parameter has had, oldest effective first. What an auditor
  asks for, and what a file on disk cannot answer."
  [events parameter]
  (->> events
       (filter #(= :parameter-changed (:event/type %)))
       (filter #(= parameter (:parameter (:data %))))
       (sort-by (comp :effective-from :data))
       (mapv :data)))
