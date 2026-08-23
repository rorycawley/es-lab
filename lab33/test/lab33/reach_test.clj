(ns lab33.reach-test
  "The lab, in one table.

  For each place a rule can live: change the configuration, and ask whether an
  answer **about the past** changes. That single question sorts the locations,
  and every other test here is one row of this one worked out in detail.

  A rule may be configured exactly where the answer is no."
  (:require [clojure.test :refer [deftest is testing]]
            [lab33.account :as account]
            [lab33.engine.evolve :as tempting]
            [lab33.fixture :as fixture]
            [lab33.policy :as policy]
            [lab33.projection :as projection]
            [lab33.rules :as rules]
            [lab33.rules.stream :as rules-stream]))

(def before
  (rules/configure {:withdrawal-fee 1M :reporting-threshold 10000M :overdraft-limit 500M}))

(def after
  (rules/configure {:withdrawal-fee 3M :reporting-threshold 15000M :overdraft-limit 0M}))

(def history
  "A closed, unchangeable past: opened, funded, one withdrawal of 12,000 that
  charged a fee of 1 under a limit of 0."
  [(fixture/opened)
   (fixture/deposited 50000M)
   (fixture/withdrawn 12000M 1M fixture/january)])

(def rule-changes
  [(rules-stream/changed :reporting-threshold 15000M fixture/june
                         "regulator" "threshold raised for 2026 H2")])

;; ---------------------------------------------------------------------------
;; Each probe answers the same question about one location: given the same
;; recorded history, does changing configuration change the answer?
;; ---------------------------------------------------------------------------

(defn- fold-reading-config    [config] (tempting/balance config history))
(defn- fold-reading-the-event [_config] (account/balance history))

(defn- decision-unstamped
  "Re-deciding an old withdrawal by reaching for today's limit, which is what
  you are forced to do when the event did not record the one it used."
  [config]
  (fixture/reason
   #(account/decide {:command/type :withdraw
                     :data {:amount 50000M
                            :withdrawal-fee  (:withdrawal-fee config)
                            :overdraft-limit (:overdraft-limit config)}}
                    {:status :open :balance 49999M})))

(defn- decision-stamped
  "Re-deciding the same withdrawal using the limit recorded on the event."
  [_config]
  (let [recorded (get-in (last history) [:metadata :rules :overdraft-limit])]
    (fixture/reason
     #(account/decide {:command/type :withdraw
                       :data {:amount 50000M
                              :withdrawal-fee 1M
                              :overdraft-limit recorded}}
                      {:status :open :balance 49999M}))))

(defn- recorded-history [config]
  ;; A policy cannot write. Whatever it asks for, the past is the past.
  (policy/react-to-all config history)
  history)

(defn- current-view [config]     (projection/flagged config history))
(defn- as-of-view   [_config]    (projection/flagged-as-of rule-changes history))

(def locations
  [{:location "evolve reading configuration"  :probe fold-reading-config    :reaches-past? true}
   {:location "evolve reading the event"      :probe fold-reading-the-event :reaches-past? false}
   {:location "decide, parameter unstamped"   :probe decision-unstamped     :reaches-past? true}
   {:location "decide, parameter stamped"     :probe decision-stamped       :reaches-past? false}
   {:location "policy"                        :probe recorded-history       :reaches-past? false}
   {:location "projection, current view"      :probe current-view           :reaches-past? true}
   {:location "projection, as-of view"        :probe as-of-view             :reaches-past? false}])

(deftest configuration-reaches-the-past-in-exactly-these-places-test
  (doseq [{:keys [location probe reaches-past?]} locations]
    (testing location
      (let [answered-before (probe before)
            answered-after  (probe after)]
        (if reaches-past?
          (is (not= answered-before answered-after)
              (str location " — expected the answer to move, and it did not"))
          (is (= answered-before answered-after)
              (str location " — configuration reached an answer about the past")))))))

(deftest the-forbidden-one-is-the-fold-test
  ;; Singled out because it is the only row where the consequence is silent
  ;; and permanent. The others either refuse loudly or are rebuildable.
  ;; 50000 deposited, 12000 withdrawn, and a fee that is nobody's business but
  ;; the configuration's.
  (is (= 37999M (tempting/balance before history)))
  (is (= 37997M (tempting/balance after history))
      "the same three events, a different balance, and nothing recorded why")
  (testing "the honest fold cannot be moved"
    (is (= 37999M (account/balance history))
        "because it takes the fee from the fact, where the decision put it")))

(deftest and-the-safe-one-is-the-policy-test
  ;; The policy's output does change — that is the point of configuring it.
  ;; What matters is that the change is confined to what it *asks for*.
  (let [asked-before (policy/react-to-all before history)
        asked-after  (policy/react-to-all after history)]
    (is (= (mapv :command/type asked-before) (mapv :command/type asked-after))
        "the same requests are made")
    (is (= (mapv :command/id asked-before) (mapv :command/id asked-after))
        "with the same identities, so redelivery still deduplicates")
    (is (not= asked-before asked-after)
        "and different contents, which is what configuring it was for")))
