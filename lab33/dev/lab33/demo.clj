(ns lab33.demo
  "One threshold change, and everywhere it reaches.

  The suite asserts these properties; this shows them moving."
  (:gen-class)
  (:require [lab33.account :as account]
            [lab33.engine.evolve :as tempting]
            [lab33.engine.predicate :as predicate]
            [lab33.policy :as policy]
            [lab33.projection :as projection]
            [lab33.rules :as rules]
            [lab33.rules.stream :as rules-stream])
  (:import (java.time Instant)))

(def rule "  ──────────────────────────────────────────────────────────────")

(defn- act [n title]
  (println)
  (println (str "  " n ". " title))
  (println rule))

(defn- show [label value]
  (println (format "     %-36s %s" label value)))

(defn- at [s] (Instant/parse s))

(def january (at "2026-01-15T10:00:00Z"))
(def june    (at "2026-06-15T10:00:00Z"))
(def october (at "2026-10-15T10:00:00Z"))

(defn- withdrawal [id amount fee occurred]
  {:event/id    id
   :event/type  :money-withdrawn
   :occurred-at occurred
   :data        {:amount amount :fee fee}
   :metadata    {:rules {:overdraft-limit 0M}}})

(def history
  [{:event/id #uuid "00000000-0000-4000-8000-000000000001"
    :event/type :account-opened :occurred-at january :data {:holder "Ada"}}
   {:event/id #uuid "00000000-0000-4000-8000-000000000002"
    :event/type :money-deposited :occurred-at january :data {:amount 60000M}}
   (withdrawal #uuid "00000000-0000-4000-8000-000000000003" 12000M 1M january)
   (withdrawal #uuid "00000000-0000-4000-8000-000000000004" 12000M 1M october)])

(def before
  (rules/configure {:reporting-threshold 10000M :withdrawal-fee 1M :sweep-amount 100M}))

(def after
  (rules/configure {:reporting-threshold 15000M :withdrawal-fee 3M :sweep-amount 250M}))

(def rule-changes
  [(rules-stream/changed :reporting-threshold 15000M june
                         "regulator" "statutory instrument 2026/114")])

(defn -main [& _]
  (println)
  (println "  Lab 33 — rules by configuration")
  (println)
  (println "  Lab 32 hard-coded a reporting threshold of 10,000. The regulator")
  (println "  has raised it to 15,000, effective June. Two withdrawals of 12,000")
  (println "  are already recorded: one in January, one in October.")

  (act 1 "evolve, reading configuration — forbidden")
  (show "balance under the old fee" (tempting/balance before history))
  (show "balance under the new fee" (tempting/balance after history))
  (println)
  (println "     The same four events. A closed account's balance moved because")
  (println "     somebody edited a file, and nothing recorded that it did.")

  (act 2 "evolve, reading the event — safe")
  (show "balance" (account/balance history))
  (println)
  (println "     There is no configuration that reaches this. The fee is on the")
  (println "     fact, put there by the decision that charged it.")

  (act 3 "decide — a parameter is an input, and gets recorded")
  (let [[event] (account/decide {:command/type :withdraw
                                 :data {:amount 300M :withdrawal-fee 2M
                                        :overdraft-limit 500M}}
                                {:status :open :balance 100M})]
    (show "permitted, and :data records" (pr-str (:data event)))
    (show "while :metadata records why" (pr-str (:metadata event))))
  (println)
  (println "     The fee is part of what happened. The limit is why it was")
  (println "     allowed. Both are needed to re-run the decision in five years.")

  (act 4 "policy — configuration's proper home")
  (let [asked-before (policy/react-to-all before history)
        asked-after  (policy/react-to-all after history)
        amounts      #(mapv (comp :amount :data) %)
        ids          #(mapv :command/id %)
        balance-was  (account/balance history)]
    (show "old configuration sweeps" (amounts asked-before))
    (show "new configuration sweeps" (amounts asked-after))
    (show "the requests differ" (not= asked-before asked-after))
    (show "the command ids do not" (= (ids asked-before) (ids asked-after)))
    (show "balance after reacting twice" (account/balance history))
    (show "  which is what it was" balance-was))
  (println)
  (println "     Different requests, identical ids so redelivery still")
  (println "     deduplicates, and not one recorded fact touched. A wrong")
  (println "     number here becomes a command the aggregate refuses.")

  (act 5 "projection — the same table answers two questions")
  (show "current view, old threshold" (count (projection/flagged before history)))
  (show "current view, new threshold" (count (projection/flagged after history)))
  (show "as-of view" (count (projection/flagged-as-of rule-changes history)))
  (doseq [row (projection/flagged-as-of rule-changes history)]
    (show (str "  " (:occurred-at row)) (str (:amount row) " vs " (:threshold-applied row))))
  (println)
  (println "     Rebuilding the current view unreported a January transaction")
  (println "     because of a change made in June. The as-of view cannot be moved.")

  (act 6 "the rule as data — what the closed check keeps out")
  (let [reportable [:and [:> :amount 10000M] [:= :direction "debit"]]
        typo       [:and [:> :ammount 10000M] [:= :direction "debit"]]
        movement   {:amount 900000M :direction "debit"}]
    (show "reads back as" (predicate/explain reportable))
    (show "matches a 900,000 debit" (predicate/evaluate reportable movement))
    (show "one letter wrong, same movement" (predicate/evaluate typo movement))
    (show "accepted as configuration?" (rules/valid? {:flag-when reportable})))
  (println)
  (println "     The typo throws nothing. It matches nothing, forever, and the")
  (println "     report it feeds looks like a quiet month.")

  (act 7 "the rules, as a stream of their own")
  (doseq [change (rules-stream/history rule-changes :reporting-threshold)]
    (show (str (:effective-from change)) (str (:value change) " — " (:changed-by change)
                                              ", " (:reason change))))
  (show "in force in January" (rules/parameter (rules-stream/as-of rule-changes january)
                                               :reporting-threshold))
  (show "in force in October" (rules/parameter (rules-stream/as-of rule-changes october)
                                               :reporting-threshold))
  (println)
  (println "     Who changed it, why, and from when. A file on disk holds one")
  (println "     value; this holds every value it has ever had.")
  (println))
