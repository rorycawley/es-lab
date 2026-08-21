(ns lab18.as-of
  "Asking the log what was true at a moment — and noticing that there are two
  different moments, which give two different right answers.

  Lab 1 argued that `:event/occurred-at` and `:recorded-at` must not be
  collapsed, because doing so 'silently corrupts every question about when'.
  This is the lab where the question finally gets asked."
  (:require [lab18.store :as store]))

;; ---------------------------------------------------------------------------
;; Three cursors, and they are not interchangeable.
;; ---------------------------------------------------------------------------

(defn up-to-version
  "The stream as it stood after its Nth event.

  The simplest as-of there is: no clock involved, just a prefix. This is the
  one to prefer whenever the question can be phrased in terms of a version,
  because it has no timestamp ambiguity at all."
  [log stream-id version]
  (->> (store/stream log stream-id)
       (filter #(<= (:stream/version %) version))
       vec))

(defn- not-after?
  [^java.util.Date a ^java.util.Date b]
  (not (.after a b)))

(defn as-known-on
  "**Transaction time.** Everything the store had *recorded* by `instant`.

  Answers: *what did we believe on the 5th?* — which is what an auditor asks,
  and what a support call about a wrong invoice needs. It is stable: a fact
  recorded tomorrow never changes what this returns for last Tuesday."
  [log stream-id instant]
  (->> (store/stream log stream-id)
       (filter #(not-after? (get-in % [:metadata :recorded-at]) instant))
       vec))

(defn as-happened-by
  "**Valid time.** Everything that had *occurred* by `instant`, however late we
  found out.

  Answers: *what do we now know was true on the 5th?* — which is what a
  reconciliation or a stock count needs. It is not stable, and that is not a
  bug: learning something new about last Tuesday is supposed to change it."
  [log stream-id instant]
  (->> (store/stream log stream-id)
       (filter #(not-after? (:event/occurred-at %) instant))
       (sort-by :event/occurred-at)
       vec))

;; ---------------------------------------------------------------------------
;; Reconstructing a decision.
;;
;; `decide` is pure, so feeding a command back the state it saw reproduces its
;; outcome exactly — provided you also feed it the rules it ran under. Miss
;; that and you have not explained the decision, you have replaced it.
;; ---------------------------------------------------------------------------

(defn state-before
  "The state a command at `version` was decided against."
  [log stream-id version replay]
  (replay (up-to-version log stream-id (dec version))))

(defn reconstruct
  "Re-run a decision. Returns `{:events …}` or `{:refused reason}`."
  [decide command state rules]
  (try
    {:events (decide command state rules)}
    (catch clojure.lang.ExceptionInfo e
      {:refused (ex-message e) :data (ex-data e)})))
