(ns lab33.projection
  "Lab 32's compliance read model, with the threshold taken out of the `def`
  it was hard-coded in — and the question that produced this lab.

  A projection is derived and rebuildable, so reconfiguring and rebuilding is
  the ordinary way to change one. The catch is that *rebuild* and *reclassify*
  are the same operation here, and whether that is correct depends entirely on
  the question being asked:

    what is reportable now?          use today's threshold.        `flagged`
    what was reportable in March?    use March's threshold.        `flagged-as-of`

  Both are legitimate. Building only the first and then using it to answer the
  second is how a compliance report for a closed year quietly changes."
  (:require [lab33.rules :as rules]
            [lab33.rules.stream :as rules-stream])
  (:import (java.time Instant)))

(defn- movement
  "The fields a classification rule may see. A movement, not an event: the
  projection reads what happened, never the metadata explaining why it was
  allowed."
  [{:keys [event/type data occurred-at]}]
  (when (= :money-withdrawn type)
    {:amount      (:amount data)
     :fee         (:fee data)
     :direction   "debit"
     :occurred-at occurred-at}))

(defn reportable?
  "The rule, as a function of a threshold and an amount.

  A named function taking its parameter as an argument, which is the shape
  this whole lab is arguing for. It is testable without configuration, and the
  two projections below differ only in where they get the number."
  [threshold amount]
  (pos? (compare amount threshold)))

(defn flagged
  "The current view: every movement classified against one threshold.

  Correct for *what is reportable now*, and rebuilt from scratch whenever the
  threshold moves. That reclassification is the intended behaviour here, and
  the reason this function takes the config rather than reaching for it is so
  a test can hold two answers side by side."
  [config events]
  (into []
        (comp (keep movement)
              (filter #(reportable? (rules/parameter config :reporting-threshold)
                                    (:amount %))))
        events))

(defn flagged-as-of
  "The as-of view: every movement classified against the threshold that was in
  force **when it occurred**.

  This is the one that survives a regulator asking about a closed year, and it
  is not achievable from configuration at all — it needs the parameter's own
  history, which is why `rules/stream.clj` exists. A file on disk holds one
  value; this holds every value it has had and when each took effect.

  Note that changing the threshold today cannot alter a single row of this
  answer. That is what makes it as-of."
  [rule-events events]
  (into []
        (keep (fn [event]
                (when-let [m (movement event)]
                  (let [^Instant when-it-happened (:occurred-at m)
                        threshold (-> (rules-stream/as-of rule-events when-it-happened)
                                      (rules/parameter :reporting-threshold))]
                    (when (reportable? threshold (:amount m))
                      (assoc m :threshold-applied threshold))))))
        events))
