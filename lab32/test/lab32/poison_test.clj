(ns lab32.poison-test
  "Acceptance test 6 — a message nobody can handle.

  Two properties, and the second is the one that is easy to lose. A message
  whose handler always throws must eventually stop being retried; and while it
  is being retried, it must not hold up anything behind it. A queue where one
  bad message stalls the rest is not a queue, it is a single point of failure
  with a table attached.

  A note on counts. The suite runs with `:backoff-seconds 0`, so a failed
  message's `next_attempt_at` is `now()` and it is immediately claimable
  again -- which means one `work-inboxes!` call retries it until its budget is
  gone, rather than once. That is the correct behaviour for a zero backoff and
  it makes the assertions below read oddly at first: three messages with a
  budget of three produce nine failures in one pass. Nine is the evidence.
  Three messages sharing a fate would have produced three."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.compliance.projections :as projections]
            [lab32.fixture :as fixture]
            [lab32.money :as money]
            [lab32.system :as system]))

(def ^:private budget 3)

(defn- always-throws [_tx _message]
  (throw (ex-info "this handler is broken" {:reason :deliberate})))

(defn- poisoned
  "A system whose Compliance handler always throws, with a small attempt
  budget so the test observes exhaustion rather than the passage of time."
  [f]
  (fixture/with-system
    {:config  {:inbox {:max-attempts budget}}
     :options {:handlers {:compliance always-throws}}}
    f))

(defn- deposit! [sys amount]
  (let [account (random-uuid)]
    (accounts/open-account! (fixture/accounts sys) {:account-id account :holder "Ada"})
    (accounts/deposit! (fixture/accounts sys) {:account-id account :amount amount})
    account))

(deftest a-message-that-always-fails-reaches-the-dead-letter-state-test
  (poisoned
   (fn [sys]
     (deposit! sys 12000)
     (system/dispatch! sys)
     (is (= 1 (count (fixture/inbox-rows))))

     (testing "it is retried until its budget is spent"
       (is (= {:handled 0 :failed budget} (:compliance (system/work-inboxes! sys)))))

     (testing "and then it stops being retried"
       (let [row (first (fixture/inbox-rows))]
         (is (= "FAILED" (:status row)))
         (is (= budget (:attempts row)))
         (is (= "deliberate: this handler is broken" (:last-error row))
             "the reason as well as the message, so dead letters can be grouped"))

       (is (= {:handled 0 :failed 0} (:compliance (system/work-inboxes! sys)))
           "a FAILED message falls outside the pending index and is not claimed again"))

     (testing "the read model was never written"
       (is (zero? (count (fixture/flagged-rows))))))))

(deftest a-poison-message-does-not-block-the-messages-behind-it-test
  (poisoned
   (fn [sys]
     (dotimes [_ 3] (deposit! sys 12000))
     (system/dispatch! sys)
     (is (= 3 (count (fixture/inbox-rows))))

     (testing "each message spends its own budget, not a shared one"
       (is (= {:handled 0 :failed (* 3 budget)} (:compliance (system/work-inboxes! sys)))
           "three messages times three attempts; a shared failure domain would give three"))

     (is (= 3 (count (fixture/dead-letter-rows)))
         "all three exhausted their own budget, none blocked another")

     (testing "and each carries its own attempt count"
       (is (= [budget budget budget] (mapv :attempts (fixture/dead-letter-rows))))))))

(deftest a-healthy-message-passes-a-poisoned-one-test
  ;; The property stated the other way round, which is the one an operator
  ;; cares about: the broken thing must not stop the working thing.
  (fixture/with-system
    {:config  {:inbox {:max-attempts budget}}
     :options {:handlers
               {:compliance
                (fn [tx {:keys [payload] :as message}]
                  (if (zero? (compare (money/of 13000) (:amount payload)))
                    (throw (ex-info "this one is poison" {:reason :deliberate}))
                    ;; The real projection for everything else, so this is a
                    ;; test about one bad message rather than about a fake
                    ;; consumer.
                    (projections/handle-transaction-recorded! tx message)))}}}
    (fn [sys]
      (deposit! sys 13000)                      ; poison
      (deposit! sys 14000)                      ; fine
      (system/dispatch! sys)

      (let [outcome (:compliance (system/work-inboxes! sys))]
        (is (= 1 (:handled outcome)))
        (is (= budget (:failed outcome))))

      (is (= 1 (count (fixture/flagged-rows)))
          "the good message was projected on the same pass the bad one failed")
      (is (= 1 (count (fixture/dead-letter-rows)))))))

(deftest a-dead-letter-can-be-revived-test
  ;; What the FAILED state is for. It is not a bin, it is a queue that has
  ;; stopped -- and once the handler is fixed, putting the message back is one
  ;; UPDATE. Nothing has to be re-derived, because the message is still there.
  (poisoned
   (fn [sys]
     (deposit! sys 12000)
     (system/dispatch! sys)
     (system/work-inboxes! sys)
     (is (= 1 (count (fixture/dead-letter-rows))))

     (fixture/query "UPDATE compliance.inbox
                        SET status = 'PENDING', attempts = 0, next_attempt_at = now()
                      WHERE status = 'FAILED'")

     ;; The same system, still with the broken handler: revival is not a fix.
     (is (= {:handled 0 :failed budget} (:compliance (system/work-inboxes! sys))))
     (is (= 1 (count (fixture/dead-letter-rows)))))))
