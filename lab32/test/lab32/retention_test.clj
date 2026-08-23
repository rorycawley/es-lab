(ns lab32.retention-test
  "§7 — what gets deleted, and the one thing that never does.

  The outbox and the inboxes are queues: a row in them is a work item, and
  once the work is done the row is rubbish. `accounts.event_stream` is not a
  queue, and the difference is the whole reason this lab exists. A system that
  cannot tell those two apart either keeps its queues forever, or prunes its
  history."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.accounts.api :as accounts]
            [lab32.fixture :as fixture]
            [lab32.messaging.inbox :as inbox]
            [lab32.messaging.outbox :as outbox]
            [lab32.system :as system]))

(defn- age-the-queues!
  "Backdate everything, as if a day had passed."
  [hours]
  (fixture/query (str "UPDATE messaging.outbox
                          SET processed_at = processed_at - interval '" hours " hours',
                              created_at   = created_at   - interval '" hours " hours'"))
  (fixture/query (str "UPDATE compliance.inbox
                          SET received_at = received_at - interval '" hours " hours'")))

(defn- deposit! [sys amount]
  (let [account (random-uuid)]
    (accounts/open-account! (system/accounts-module sys) {:account-id account :holder "Ada"})
    (accounts/deposit! (system/accounts-module sys) {:account-id account :amount amount})
    account))

(deftest processed-queue-rows-are-pruned-and-the-history-is-not-test
  (fixture/with-system
    (fn [sys]
      (dotimes [_ 3] (deposit! sys 12000))
      (system/settle! sys)

      (is (= 3 (count (fixture/outbox-rows))))
      (is (= 3 (count (fixture/inbox-rows))))
      (is (= 6 (count (fixture/event-rows))) "three opens and three deposits")
      (is (= 3 (count (fixture/flagged-rows))))

      (testing "nothing is old enough yet"
        (is (zero? (outbox/prune! (system/pool-for (:datasources sys) :messaging) 24)))
        (is (zero? (inbox/prune! (system/pool-for (:datasources sys) :compliance)
                                 "compliance" 24))))

      (age-the-queues! 25)

      (testing "a day later the queues are empty"
        (is (= 3 (outbox/prune! (system/pool-for (:datasources sys) :messaging) 24)))
        (is (= 3 (inbox/prune! (system/pool-for (:datasources sys) :compliance)
                               "compliance" 24)))
        (is (zero? (count (fixture/outbox-rows))))
        (is (zero? (count (fixture/inbox-rows)))))

      (testing "and everything that mattered is still here"
        (is (= 6 (count (fixture/event-rows)))
            "the event stream is never pruned; there is no code that could")
        (is (= 3 (count (fixture/flagged-rows)))
            "and neither is a read model built from it")))))

(deftest a-message-still-waiting-is-never-pruned-test
  ;; The dangerous version of a retention job: one that deletes by age alone.
  ;; A message that has been stuck for a day is exactly the message somebody
  ;; needs to find, and deleting it turns a visible backlog into a silent loss.
  (fixture/with-system
    (fn [sys]
      (deposit! sys 12000)
      (age-the-queues! 100)

      (is (= "PENDING" (:status (first (fixture/outbox-rows)))))
      (is (zero? (outbox/prune! (system/pool-for (:datasources sys) :messaging) 24))
          "a hundred hours old and still pending: not rubbish, a problem")
      (is (= 1 (count (fixture/outbox-rows))))

      (testing "and it still delivers once somebody drains it"
        (system/settle! sys)
        (is (= 1 (count (fixture/inbox-rows))))))))

(deftest a-dead-letter-is-never-pruned-either-test
  ;; FAILED is not PROCESSED. A message nobody could handle is the single most
  ;; interesting row in the table, and an age-based sweep that took it would
  ;; destroy the evidence of the incident it belongs to.
  (fixture/with-system
    {:config  {:inbox {:max-attempts 1}}
     :options {:handlers {:compliance (fn [_ _] (throw (ex-info "broken"
                                                                {:reason :deliberate})))}}}
    (fn [sys]
      (deposit! sys 12000)
      (system/dispatch! sys)
      (system/work-inboxes! sys)
      (is (= 1 (count (fixture/dead-letter-rows))))

      (age-the-queues! 100)
      (is (zero? (inbox/prune! (system/pool-for (:datasources sys) :compliance)
                               "compliance" 24)))
      (is (= 1 (count (fixture/dead-letter-rows)))
          "the graveyard is not swept"))))

(deftest the-retention-sweep-is-wired-into-the-system-test
  ;; A prune function nobody calls is a table that grows forever, so the
  ;; scheduled component is part of the claim rather than an operational
  ;; detail. The suite runs it with no interval and drives it by hand; this
  ;; asserts the component exists and starts when asked.
  (fixture/with-system
    {:config {:retention {:interval-ms 3600000 :hours 24}}}
    (fn [sys]
      (is (some? (get-in sys [:retention :executor]))
          "the retention sweep did not start"))))
