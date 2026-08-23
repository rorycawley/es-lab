(ns lab32.isolation-test
  "The boundaries the database enforces, asserted against the database.

  Everything here could be a code review comment instead. The reason it is a
  test is that a code review comment does not fail the build in eighteen
  months when somebody adds a convenient join."
  (:require [clojure.test :refer [deftest is testing]]
            [lab32.db.datasource :as datasource]
            [lab32.fixture :as fixture]
            [lab32.system :as system]
            [next.jdbc :as jdbc]))

(defn- denied?
  "Does this statement fail with insufficient privilege (42501)?"
  [pool sql]
  (try
    (jdbc/execute! pool [sql])
    false
    (catch org.postgresql.util.PSQLException e
      (= "42501" (.getSQLState e)))))

;; ---------------------------------------------------------------------------
;; Gotcha #6
;; ---------------------------------------------------------------------------

(deftest every-pool-is-read-committed-test
  ;; `FOR UPDATE SKIP LOCKED` under REPEATABLE READ or SERIALIZABLE does not
  ;; skip -- it raises a serialization failure. The claim query would stop
  ;; being a queue and start being a source of intermittent errors under
  ;; exactly the concurrency it exists to handle.
  ;;
  ;; READ COMMITTED is Postgres's default, which is precisely why this is
  ;; asserted: a default is a thing somebody can change in a config file, in a
  ;; connection string, or by pointing the application at a differently
  ;; configured server.
  (fixture/with-system
    (fn [sys]
      (doseq [identity [:accounts :compliance :messaging]]
        (is (= "read committed"
               (datasource/isolation-level (system/pool-for (:datasources sys) identity)))
            (str (name identity) " is not READ COMMITTED"))))))

;; ---------------------------------------------------------------------------
;; Schema-per-module, enforced below the source tree
;; ---------------------------------------------------------------------------

(deftest accounts-cannot-see-compliance-test
  (fixture/with-system
    (fn [sys]
      (let [pool (system/pool-for (:datasources sys) :accounts)]
        (is (denied? pool "SELECT * FROM compliance.inbox"))
        (is (denied? pool "SELECT * FROM compliance.flagged_transactions"))))))

(deftest compliance-cannot-see-the-event-stream-test
  ;; The one that matters most, and the one most likely to be argued away. A
  ;; consumer that can read the producer's stream will eventually read it --
  ;; and then the events it was sent stop being the contract, and the
  ;; producer's internal model becomes everybody's dependency.
  (fixture/with-system
    (fn [sys]
      (let [pool (system/pool-for (:datasources sys) :compliance)]
        (is (denied? pool "SELECT * FROM accounts.event_stream"))
        (is (denied? pool "SELECT * FROM messaging.outbox"))))))

(deftest a-producer-may-write-to-the-outbox-and-not-read-it-test
  (fixture/with-system
    (fn [sys]
      (let [pool (system/pool-for (:datasources sys) :accounts)]
        (testing "reading the transport is not a producer's business"
          (is (denied? pool "SELECT * FROM messaging.outbox")))
        (testing "and neither is taking things out of it"
          (is (denied? pool "DELETE FROM messaging.outbox")))))))

(deftest the-transport-may-deliver-and-not-consume-test
  (fixture/with-system
    (fn [sys]
      (let [pool (system/pool-for (:datasources sys) :messaging)]
        (testing "it can insert into an inbox"
          (is (not (denied? pool "SELECT event_id FROM compliance.inbox"))
              "and check the id it is inserting, which ON CONFLICT requires"))
        (testing "it cannot read what it delivered"
          (is (denied? pool "SELECT payload FROM compliance.inbox"))
          (is (denied? pool "SELECT status FROM compliance.inbox")))
        (testing "it cannot work the queue it fills"
          (is (denied? pool "UPDATE compliance.inbox SET status = 'PROCESSED'")))
        (testing "and it cannot touch the read model behind it"
          (is (denied? pool "SELECT * FROM compliance.flagged_transactions")))))))

(deftest nobody-can-create-objects-in-public-test
  ;; `REVOKE CREATE ON SCHEMA public FROM PUBLIC`, asserted. Without it, any
  ;; role can create a table in `public` that shadows a name on somebody
  ;; else's search path.
  (fixture/with-system
    (fn [sys]
      (doseq [identity [:accounts :compliance :messaging]]
        (is (denied? (system/pool-for (:datasources sys) identity)
                     "CREATE TABLE public.sneaky (id int)")
            (str (name identity) " can create objects in public"))))))
