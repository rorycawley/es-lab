(ns lab32.accounts.api
  "Accounts' public module API: three commands, two queries, one transaction
  each.

  §6.1 lives in `attempt!` below and it is worth reading as the answer to a
  specific question -- *how do you record a state change and tell somebody
  about it, without a moment where one is true and the other is not?* The
  usual answer is to write the database and then publish, which has a window;
  or publish and then write, which has a worse one. This answer is that there
  is no second write. The event and the message go into one transaction against
  one database, and the transaction is the only thing that has to be correct."
  (:require [lab32.accounts.domain :as domain]
            [lab32.accounts.events :as events]
            [lab32.accounts.repository :as repository]
            [lab32.messaging.outbox :as outbox]
            [lab32.money :as money]
            [malli.core :as m]
            [next.jdbc :as jdbc]))

;; ---------------------------------------------------------------------------
;; The edge. Lab 22's rule: validate the shape here, so the domain can assume
;; it and spend its attention on rules instead.
;; ---------------------------------------------------------------------------

(def OpenAccount
  [:map {:closed true}
   [:account-id :uuid]
   [:holder [:string {:min 1}]]
   [:correlation-id {:optional true} :uuid]])

(def Movement
  [:map {:closed true}
   [:account-id :uuid]
   ;; Not `:double`, and not even `:number`. What arrives here is whatever the
   ;; HTTP edge parsed, and `money/of` is the thing that decides whether it is
   ;; an amount of money -- refusing a float rather than rounding it.
   [:amount :any]
   [:correlation-id {:optional true} :uuid]])

(defn- validate!
  [schema request]
  (when-not (m/validate schema request)
    (throw (ex-info "Malformed request"
                    {:reason  :malformed-request
                     :because (m/explain schema request)}))))

;; ---------------------------------------------------------------------------
;; The write path
;; ---------------------------------------------------------------------------

(def max-command-attempts
  "How many times a command may be re-decided after losing a race.

  Not a retry for a flaky network -- there is no network. This is specifically
  for `UNIQUE (aggregate_id, version)` rejecting an append because a concurrent
  writer took the version first. The correct response is to throw away the
  decision, re-read the stream and decide again against what is now true; the
  balance check has to happen against the real history, not a stale one.

  Twenty rather than three because acceptance test 5 puts four threads on one
  account and keeps them there. Losing twenty races in a row, with the backoff
  below widening between each, means the contention is structural -- and an
  aggregate that busy is a modelling problem (lab 16), not a retry-count
  problem. The budget exists so that the system gives up and says so, rather
  than spinning forever on a boundary somebody drew wrong."
  20)

(defn- concurrent-write-cause?
  "Walk the cause chain. next.jdbc will sometimes hand back the driver's
  exception wrapped, and a retry that only inspects the outermost throwable
  turns a routine race into a 500."
  [t]
  (loop [e t]
    (cond
      (nil? e)                          false
      (repository/concurrent-write? e)  true
      :else                             (recur (ex-cause e)))))

(defn- attempt!
  "One try at the read-decide-append-enqueue loop. All of it, or none of it."
  [{:keys [datasource new-id]} {:keys [command/type data correlation-id]}]
  (jdbc/with-transaction [tx datasource]
    (let [aggregate-id (:account-id data)
          history      (repository/history tx aggregate-id)
          state        (domain/replay history)
          decided      (domain/decide state {:command/type type :data data})
          metadata     (cond-> {} correlation-id (assoc :correlation-id (str correlation-id)))
          recorded     (repository/append! tx aggregate-id (count history) decided
                                           {:new-id new-id :metadata metadata})]
      ;; The second half of the one transaction. If this throws, the events
      ;; above are rolled back too -- acceptance test 2 forces exactly that and
      ;; asserts both tables are empty afterwards.
      (doseq [message (keep events/->integration-event recorded)]
        (outbox/enqueue! tx message))
      {:account-id aggregate-id
       :version    (+ (count history) (count recorded))
       :events     (mapv :event/type recorded)})))

(def ^:private max-backoff-ms 50)

(defn- back-off!
  "Wait a random, widening moment before re-deciding.

  Both halves are load bearing, and it took a flaky test to establish it.

  *Randomised*, because four threads that collide all re-read the stream, all
  re-decide and all append at the same instant -- so they collide again. A
  fixed delay keeps them in lockstep, it just synchronises them slightly later.

  *Widening*, because the window grows with the history. Each retry re-reads a
  longer stream, which takes longer, which makes the next collision more likely
  rather than less. A flat few milliseconds of jitter is enough at version 10
  and not at version 80: the first version of this backed off by `2 * attempt`
  milliseconds and the concurrency test failed intermittently, always late in
  the run. Doubling outruns the growth."
  [attempt]
  (Thread/sleep (long (rand-int (min max-backoff-ms (bit-shift-left 1 attempt))))))

(defn- handle!
  [context command]
  (loop [attempt 1]
    (let [outcome (try
                    [:ok (attempt! context command)]
                    (catch Throwable t
                      (if (and (concurrent-write-cause? t)
                               (< attempt max-command-attempts))
                        [:retry nil]
                        (throw t))))]
      (if (= :retry (first outcome))
        (do (back-off! attempt)
            (recur (inc attempt)))
        (second outcome)))))

;; ---------------------------------------------------------------------------
;; The public surface
;; ---------------------------------------------------------------------------

(defn- open! [context request]
  (validate! OpenAccount request)
  (handle! context {:command/type   :accounts/open-account
                    :correlation-id (:correlation-id request)
                    :data           (select-keys request [:account-id :holder])}))

(defn- movement! [context command-type request]
  (validate! Movement request)
  (handle! context {:command/type   command-type
                    :correlation-id (:correlation-id request)
                    :data           {:account-id (:account-id request)
                                     :amount     (money/of (:amount request))}}))

(defn- balance-of [{:keys [datasource]} account-id]
  (let [history (repository/history datasource account-id)]
    (when (seq history)
      (let [{:keys [status balance holder]} (domain/replay history)]
        {:account-id account-id
         :holder     holder
         :status     status
         :balance    (money/of balance)
         :version    (count history)}))))

(defn- republish-all!
  "Re-enqueue the whole stream as integration events. Phase 4's replay.

  This is the operation a broker cannot offer, and the reason `event_stream` is
  never pruned. The messages are rebuilt from the permanent record rather than
  recovered from a queue, so it works just as well a year later as a minute
  later. Returns how many messages the stream yields.

  `enqueue-once!` rather than `enqueue!`, because the ids are the domain
  events' own and any message the retention sweep has not yet removed is still
  in the outbox. Those rows are resurrected by `outbox/requeue!` under the
  transport's own identity -- Accounts holds INSERT here and nothing else, so
  it could not put a PROCESSED row back even if it wanted to."
  [{:keys [datasource]}]
  (jdbc/with-transaction [tx datasource]
    (let [messages (keep events/->integration-event (repository/everything tx))]
      (run! #(outbox/enqueue-once! tx %) messages)
      (count messages))))

(defrecord Accounts [open-account deposit withdraw balance history search republish])

(defn new-module
  ([datasource] (new-module datasource {}))
  ([datasource {:keys [new-id] :or {new-id random-uuid}}]
   (let [context {:datasource datasource :new-id new-id}]
     (->Accounts #(open! context %)
                 #(movement! context :accounts/deposit %)
                 #(movement! context :accounts/withdraw %)
                 #(balance-of context %)
                 #(repository/stream datasource %)
                 #(repository/search datasource %)
                 #(republish-all! context)))))

(defn open-account! [accounts request] ((:open-account accounts) request))
(defn deposit!      [accounts request] ((:deposit accounts) request))
(defn withdraw!     [accounts request] ((:withdraw accounts) request))
(defn balance       [accounts account-id] ((:balance accounts) account-id))
(defn history       [accounts account-id] ((:history accounts) account-id))
(defn search        [accounts criteria] ((:search accounts) criteria))
(defn republish!    [accounts] ((:republish accounts)))
