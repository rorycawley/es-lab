(ns lab33.account
  "The Decider (lab 8): `decide`, `evolve`, `initial-state`.

  Read the `ns` form. It requires nothing — and specifically it does not
  require `lab33.rules`. That absence is the lab's central claim expressed in
  the one place a reader checks first, and `architecture_test.clj` fails the
  build if it ever appears.

  Parameters still shape what this namespace does. They arrive as **inputs**:
  the overdraft limit and the withdrawal fee are fields on the command, put
  there by whoever assembled it. That is Chassaing's external-command-becomes-
  internal-command enrichment, and it is the same move [lab 8] makes by having
  `decide` return proposals rather than write them — keep the function total in
  its arguments and let the edge do the reaching.")

(def initial-state
  {:status :absent :balance 0M})

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state
;;
;; The forbidden case, and the reason it is forbidden.
;;
;; This function is replayed over history forever. If it read a fee from
;; configuration, the same stream would fold to a different balance whenever
;; somebody changed the fee — and nothing would detect it, because the fold's
;; *code* is unchanged, so lab 17's fold version still matches and a stale
;; snapshot still looks valid.
;;
;; So the fee is not read here. It is read once, at the moment of the decision,
;; and written into the event. `evolve` takes it from the fact, which makes
;; this fold deterministic for as long as the events exist.
;;
;; `engine/evolve.clj` is the version that reads configuration, kept so the
;; divergence can be measured rather than described.
;; ---------------------------------------------------------------------------

(defn evolve
  [state {:keys [event/type data]}]
  (case type
    :account-opened  (assoc state :status :open :holder (:holder data) :balance 0M)
    :money-deposited (update state :balance + (:amount data))
    ;; Both numbers come out of the event. Neither is looked up.
    :money-withdrawn (update state :balance - (+ (:amount data) (:fee data)))
    state))

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; decide : command -> state -> [event]
;; ---------------------------------------------------------------------------

(defn- refuse [reason detail]
  (throw (ex-info (str "Refused: " (name reason)) (assoc detail :reason reason))))

(defn- positive [amount]
  (cond
    (not (decimal? amount)) (refuse :amount-not-decimal {:amount amount})
    (not (pos? amount))     (refuse :amount-not-positive {:amount amount})
    :else                   amount))

(defn decide
  "`command -> state -> [event]`.

  The two configured parameters are read off the command, not out of a
  registry, and both end up recorded — but in different places, and the split
  is REFERENCE.md's rule rather than a preference.

  The **fee** goes in `:data`. A domain expert would say money left the
  account: the fee is part of what happened.

  The **overdraft limit** goes in `:metadata`. Nothing about it happened. It is
  why the withdrawal was permitted — a decision input, which is exactly what
  lab 18 says you must retain to re-run a decision and get the same answer.
  Leave it out and next year's audit re-runs this withdrawal against next
  year's limit and reaches a different verdict, which presents as a
  discrepancy rather than as a bug."
  [{:keys [command/type data]} {:keys [status balance]}]
  (case type
    :open-account
    (if (= :open status)
      (refuse :account-already-open {})
      [{:event/type :account-opened :data {:holder (:holder data)}}])

    :deposit
    (if (not= :open status)
      (refuse :account-not-open {})
      [{:event/type :money-deposited :data {:amount (positive (:amount data))}}])

    :withdraw
    (let [amount (positive (:amount data))
          fee    (:withdrawal-fee data)
          limit  (:overdraft-limit data)]
      (when (not= :open status)
        (refuse :account-not-open {}))
      (when-not (decimal? fee)
        (refuse :fee-not-supplied {:withdrawal-fee fee}))
      (when-not (decimal? limit)
        (refuse :limit-not-supplied {:overdraft-limit limit}))
      (if (neg? (+ (- balance amount fee) limit))
        (refuse :insufficient-funds {:balance balance :requested amount
                                     :fee fee :overdraft-limit limit})
        [{:event/type :money-withdrawn
          :data       {:amount amount :fee fee}
          :metadata   {:rules {:overdraft-limit limit}}}]))

    (refuse :unknown-command {:command/type type})))

(defn balance
  [events]
  (:balance (replay events)))
