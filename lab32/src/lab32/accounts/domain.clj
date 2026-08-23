(ns lab32.accounts.domain
  "The pure half, and the only place a business rule lives.

  Lab 6 introduced `evolve`, lab 8 introduced `decide`, and lab 0 set the
  criterion this namespace is still held to: no clock, no database, no id
  generator, no logging. `decide` is handed the state a fold produced and a
  command, and returns the facts that follow. Everything that makes those facts
  *particular* -- what time it is, what uuid they get -- is assigned outside,
  which is why `domain_test.clj` needs no fixture and `architecture_test.clj`
  can assert this file names nothing impure.

  Money is `BigDecimal` from end to end. See `db/json.clj` for what happens if
  it is not."
  (:require [clojure.string :as str]))

(def initial-state
  {:status :absent :balance 0M})

;; ---------------------------------------------------------------------------
;; The fold
;; ---------------------------------------------------------------------------

(defn evolve
  "Apply one event to the state. Total, and never refuses.

  An event is a fact that already happened; there is no such thing as applying
  one that should not have. All the refusing happens in `decide`, before
  anything is a fact. Unknown types are ignored rather than thrown on, because
  a fold that crashes on an event type it has not been taught about cannot
  survive its own history."
  [state {:keys [event/type data]}]
  (case type
    :accounts/account-opened  (assoc state
                                     :status :open
                                     :holder (:holder data)
                                     :balance 0M)
    :accounts/money-deposited (update state :balance + (:amount data))
    :accounts/money-withdrawn (update state :balance - (:amount data))
    state))

(defn replay
  "Fold a history into the state it implies."
  [history]
  (reduce evolve initial-state history))

;; ---------------------------------------------------------------------------
;; The decision
;; ---------------------------------------------------------------------------

(defn- refuse [reason detail]
  (throw (ex-info (str "Refused: " (name reason)) (assoc detail :reason reason))))

(defn- positive-amount
  [amount]
  (cond
    (not (decimal? amount))
    (refuse :amount-not-decimal {:amount amount})

    (not (pos? amount))
    (refuse :amount-not-positive {:amount amount})

    :else amount))

(defn decide
  "`state -> command -> [event]`. Pure, and the events have no ids yet.

  Returning a vector rather than a single event is lab 5's rule and it costs
  nothing here: every command below happens to produce exactly one. The shape
  is what matters -- a command that later needs to produce two facts should not
  require changing every caller's expectations about arity."
  [{:keys [status balance] :as _state} {:keys [command/type data]}]
  (case type
    :accounts/open-account
    (if (= :open status)
      (refuse :account-already-open {:account-id (:account-id data)})
      (do (when (str/blank? (:holder data))
            (refuse :holder-required {}))
          [{:event/type :accounts/account-opened
            :data       {:account-id (:account-id data)
                         :holder     (:holder data)}}]))

    :accounts/deposit
    (if (not= :open status)
      (refuse :account-not-open {:account-id (:account-id data)})
      [{:event/type :accounts/money-deposited
        :data       {:account-id (:account-id data)
                     :amount     (positive-amount (:amount data))}}])

    :accounts/withdraw
    (cond
      (not= :open status)
      (refuse :account-not-open {:account-id (:account-id data)})

      ;; The invariant, and the reason this system has an aggregate at all.
      ;;
      ;; It is checked against a state folded from the stream inside the same
      ;; transaction that will append to it, and the UNIQUE (aggregate_id,
      ;; version) constraint is what makes that check mean something: if a
      ;; concurrent writer moved the balance after this fold, the append is
      ;; rejected and the whole command is re-decided against what is now true.
      ;; Lab 16 is about which invariants have to be immediate. This one does,
      ;; because the alternative is an overdraft you find out about later.
      (< balance (positive-amount (:amount data)))
      (refuse :insufficient-funds {:account-id (:account-id data)
                                   :balance    balance
                                   :requested  (:amount data)})

      :else
      [{:event/type :accounts/money-withdrawn
        :data       {:account-id (:account-id data)
                     :amount     (:amount data)}}])

    (refuse :unknown-command {:command/type type})))
