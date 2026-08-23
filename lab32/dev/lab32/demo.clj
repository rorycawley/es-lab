(ns lab32.demo
  "What the machinery looks like when you watch it move.

  The suite asserts these properties; this one shows them. Every act starts
  from a still system and prints the tables afterwards, so the claims in the
  README have something you can run beside them."
  (:gen-class)
  (:require [lab32.accounts.api :as accounts]
            [lab32.compliance.api :as compliance]
            [lab32.messaging.dispatcher :as dispatcher]
            [lab32.messaging.router :as router]
            [lab32.money :as money]
            [lab32.postgres :as postgres]
            [lab32.system :as system]))

(def rule "  ──────────────────────────────────────────────────────────────")

(defn- act [n title]
  (println)
  (println (str "  " n ". " title))
  (println rule))

(defn- show
  [label value]
  (println (format "     %-34s %s" label value)))

(defn- queues []
  (show "outbox" (str (count (filter #(= "PENDING" (:status %)) (postgres/outbox-rows)))
                      " pending, "
                      (count (filter #(= "PROCESSED" (:status %)) (postgres/outbox-rows)))
                      " processed"))
  (show "compliance.inbox" (str (count (postgres/inbox-rows)) " rows, "
                                (count (postgres/dead-letter-rows)) " dead"))
  (show "flagged_transactions" (count (postgres/flagged-rows))))

(defn- open! [sys holder]
  (let [account (random-uuid)]
    (accounts/open-account! (system/accounts-module sys) {:account-id account :holder holder})
    account))

(defn- wait-for
  [check timeout-ms]
  (let [started  (System/nanoTime)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (check) (quot (- (System/nanoTime) started) 1000000)
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 2) (recur))
        :else nil))))

(defn- time-one-delivery
  "Start a system with the given config, make a deposit, and time how long it
  takes to reach the inbox. Its own system each time, so the two numbers are
  measured the same way."
  [listener?]
  (postgres/truncate!)
  (postgres/set-notify-trigger! listener?)
  (let [sys (system/start (postgres/config
                           {:reconciler {:interval-ms 2000}
                            :inbox      {:interval-ms 25}
                            :listener   {:enabled? listener?}}))]
    (try
      (let [account (open! sys "Grace")]
        (accounts/deposit! (system/accounts-module sys) {:account-id account :amount 12000})
        (or (wait-for #(pos? (count (postgres/inbox-rows))) 10000) :timed-out))
      (finally
        (system/stop sys)))))

(defn- latency-without-notify [] (time-one-delivery false))
(defn- latency-with-notify    [] (time-one-delivery true))

(defn- out-of-order
  "Deliver 20 accounts' movements with eight dispatchers, and count how many
  accounts arrived in a different order than they were published."
  [strategy]
  (postgres/truncate!)
  (let [sys (system/start (postgres/config {:dispatcher {:claim-strategy strategy}}))]
    (try
      (let [module   (system/accounts-module sys)
            accounts (repeatedly 20 random-uuid)]
        (doseq [account accounts]
          (accounts/open-account! module {:account-id account :holder "Ada"}))
        (run! deref (mapv (fn [group]
                            (future (doseq [account group, i (range 15)]
                                      (accounts/deposit! module {:account-id account
                                                                 :amount (+ 100 i)}))))
                          (partition-all 5 accounts)))
        ;; Eight *independent* dispatchers, each with its own semaphore. Eight
        ;; futures calling the system's single dispatcher would coalesce
        ;; inside one JVM and never interleave -- which is the guard doing its
        ;; job, and would quietly make this act prove nothing.
        (let [config (assoc (:dispatcher (postgres/config)) :claim-strategy strategy)
              routes (router/router system/contracts)
              pool   (system/pool-for (:datasources sys) :messaging)
              many   (mapv (fn [_] (dispatcher/dispatcher pool routes config)) (range 8))]
          (run! deref (mapv (fn [d] (future (dotimes [_ 10] (dispatcher/drain! d)))) many)))
        (let [published (group-by :partition-key (postgres/outbox-rows))
              delivered (group-by :partition-key (postgres/inbox-rows))]
          (count (for [[partition rows] published
                       :when (not= (mapv :event-id (sort-by :seq rows))
                                   (mapv :event-id (sort-by :seq (get delivered partition []))))]
                   partition))))
      (finally
        (system/stop sys)))))

(defn -main [& _]
  (postgres/truncate!)
  (let [sys (system/start (postgres/config))]
    (try
      (println)
      (println "  Lab 32 — Postgres as the event bus")

      (act 1 "One transaction: the state change and the message together")
      (let [ada (open! sys "Ada")]
        (accounts/deposit! (system/accounts-module sys) {:account-id ada :amount 12000})
        (show "events recorded" (count (postgres/event-rows)))
        (show "messages queued" (count (postgres/outbox-rows)))
        (println)
        (println "     Two events, one message. `account-opened` is Accounts' own")
        (println "     business; only the movement is anybody else's.")

        (act 2 "Nothing has moved, because nothing has drained it yet")
        (queues)

        (act 3 "The reconciler's pass — the same drain! the fast path calls")
        (system/settle! sys)
        (queues)
        (let [[flagged] (compliance/flagged-transactions (system/compliance-module sys))]
          (show "flagged amount" (str (:amount flagged) " " (:direction flagged))))

        (act 4 "A movement under the threshold is delivered and not flagged")
        (accounts/deposit! (system/accounts-module sys) {:account-id ada :amount 9999})
        (system/settle! sys)
        (queues)
        (println)
        (println "     The inbox row is PROCESSED. \"I looked and there was nothing")
        (println "     to do\" is not the same state as \"I have not looked yet\".")

        (act 5 "The invariant, checked against a folded history")
        (let [balance (:balance (accounts/balance (system/accounts-module sys) ada))]
          (show "balance" balance)
          (show "withdrawing 50,000"
                (try (accounts/withdraw! (system/accounts-module sys)
                                         {:account-id ada :amount 50000})
                     "allowed"
                     (catch clojure.lang.ExceptionInfo e
                       (str "refused: " (name (:reason (ex-data e))))))))
        (show "events after the refusal" (count (postgres/event-rows)))

        (act 6 "Redelivery is a no-op, not a duplicate")
        (postgres/query "UPDATE messaging.outbox SET status = 'PENDING', processed_at = NULL")
        (show "outbox reset to" (str (count (postgres/outbox-rows)) " pending"))
        (system/settle! sys)
        (queues)
        (println)
        (println "     Every message was delivered a second time. The inbox's unique")
        (println "     constraint absorbed all of it.")

        (act 7 "The ad-hoc query a broker cannot answer")
        (let [big (accounts/search (system/accounts-module sys)
                                   {:event-type "accounts/money-deposited"
                                    :min-amount (money/of 10000)})]
          (show "deposits over 10,000, all history" (count big))
          (doseq [event big]
            (show (str "  " (:aggregate/version event)) (get-in event [:data :amount]))))
        (println)
        (println "     A JSONB predicate over the whole event stream, which is never")
        (println "     pruned. The outbox that carried these was a queue and is not.")

        (act 8 "Throw the read model away and rebuild it from the stream")
        (let [before (count (postgres/flagged-rows))]
          (show "flagged before" before)
          (postgres/query "DELETE FROM messaging.outbox")
          (show "outbox deleted" "as a broker's retention window would")
          (let [republished (system/replay! sys :compliance)]
            (show "flagged after clearing" (count (postgres/flagged-rows)))
            (show "messages rebuilt from events" republished))
          (system/settle! sys)
          (show "flagged after rebuild" (count (postgres/flagged-rows)))
          (println)
          (println "     The queue was gone and it did not matter. The messages were")
          (println "     re-derived from facts, not recovered from transport."))
        (println))
      (finally
        (system/stop sys)))

    ;; Last, because it starts fresh systems of its own and truncates between
    ;; them -- the two numbers have to be measured the same way to be worth
    ;; comparing at all.
    (act 9 "The doorbell, timed against the polling loop")
    (show "reconciler only (2s interval)" (str (latency-without-notify) "ms to the inbox"))
    (show "with LISTEN/NOTIFY" (str (latency-with-notify) "ms"))
    (println)
    (println "     The same drain!, called by a signal instead of by a timer.")
    (println "     Turn the trigger off and nothing is lost -- only speed.")

    (act 10 "Ordering, with eight dispatchers on twenty accounts")
    (doseq [[label strategy] [["SKIP LOCKED (phase 1)" :skip-locked]
                              ["partition lock (phase 3)" :partition]]]
      (show label (str (out-of-order strategy) " of 20 accounts delivered out of order")))
    (println)
    (println "     Both delivered every message exactly once. Only one of them")
    (println "     can tell you what happened first.")
    (println)
    (shutdown-agents)))
