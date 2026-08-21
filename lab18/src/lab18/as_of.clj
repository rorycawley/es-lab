(ns lab18.as-of
  "Asking the log what was true at a moment—and noticing that transaction
  time and valid time answer different questions."
  (:require [lab18.store :as store]))

(defn up-to-version
  "The stream prefix through `version`, without timestamp ambiguity."
  [log stream-id version]
  (when-not (and (integer? version) (not (neg? version)))
    (throw (ex-info "Version must be a non-negative integer"
                    {:version version})))
  (->> (store/stream log stream-id)
       (filter #(<= (:stream/version %) version))
       vec))

(defn- require-instant
  [instant]
  (when-not (inst? instant)
    (throw (ex-info "As-of cutoff must be an instant"
                    {:instant instant}))))

(defn- not-after?
  [label value instant]
  (when-not (inst? value)
    (throw (ex-info "Event is missing a valid temporal value"
                    {:field label :value value})))
  (not (.after ^java.util.Date value ^java.util.Date instant)))

(defn valid-at
  "When this fact is effective in the domain history.

  Most facts are effective when they occurred. A correction occurs when it is
  made but may explicitly amend an earlier effective time."
  [event]
  (or (get-in event [:data :effective-at])
      (:event/occurred-at event)))

(defn as-known-on
  "**Transaction time.** Facts the store had recorded by `instant`.

  This answers what was known at the cutoff. Its stability relies on the store
  assigning transaction time authoritatively and not accepting backdating."
  [log stream-id instant]
  (require-instant instant)
  (->> (store/stream log stream-id)
       (filter #(not-after? :recorded-at
                            (get-in % [:metadata :recorded-at])
                            instant))
       vec))

(defn as-happened-by
  "**Valid time.** Facts effective by `instant`, using everything known now.

  This selection may change when late facts arrive. It supports this lab's
  commutative stock projection; it is not a general aggregate rehydration
  algorithm. Arbitrary transitions must retain stream order and may not remain
  meaningful after temporal filtering."
  [log stream-id instant]
  (require-instant instant)
  (->> (store/stream log stream-id)
       (filter #(not-after? :valid-at (valid-at %) instant))
       (sort-by (juxt valid-at :stream/version))
       vec))

(defn state-before
  "State before a one-event command recorded event `version`.

  General reconstruction should retain the command's expected stream version;
  an arbitrary event version may sit inside a multi-event decision."
  [log stream-id version replay]
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "Decision event version must be a positive integer"
                    {:version version})))
  (when (> version (store/current-version log stream-id))
    (throw (ex-info "Decision event version does not exist"
                    {:stream/id stream-id
                     :version version})))
  (replay (up-to-version log stream-id (dec version))))

(defn state-at-version
  "Fold through the exact stream version retained as a decision input."
  [log stream-id version replay]
  (when-not (and (integer? version) (not (neg? version)))
    (throw (ex-info "Decision input version must be a non-negative integer"
                    {:version version})))
  (when (> version (store/current-version log stream-id))
    (throw (ex-info "Decision input version does not exist"
                    {:stream/id stream-id
                     :version version})))
  (replay (up-to-version log stream-id version)))

(def business-refusal-reasons
  #{:sold-out
    :reserved-stock-only
    :unknown-sale
    :sale-already-corrected
    :correction-mismatch
    :correction-effective-time-mismatch
    :same-flavour-correction})

(defn reconstruct
  "Re-run a decision, returning named business refusals as data.

  Unexpected application or programming failures are propagated."
  [decide command state rules]
  (try
    {:events (decide command state rules)}
    (catch clojure.lang.ExceptionInfo e
      (if (contains? business-refusal-reasons (:reason (ex-data e)))
        {:refused (ex-message e) :data (ex-data e)}
        (throw e)))))
