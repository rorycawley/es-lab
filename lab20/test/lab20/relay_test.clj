(ns lab20.relay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [lab20.fixture :as fixture]
            [lab20.handler :as handler]
            [lab20.inbox :as inbox]
            [lab20.outbox :as outbox]
            [lab20.relay :as relay]
            [next.jdbc :as jdbc]))

(use-fixtures :each fixture/with-store)

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- sell-the-last-cone!
  "Leaves two pending messages in the outbox."
  [ds]
  (handler/handle! ds truck-1 gen-id t0 (command :load-truck {:flavour "vanilla" :quantity 1}))
  (handler/handle! ds truck-1 gen-id t0 (command :buy-flavour {:flavour "vanilla"})))

;; A downstream effect that is NOT the consumer's read model — the case lab 12's
;; `:seen` set could not have protected.
(defn- record-notification!
  [tx row]
  (jdbc/execute-one! tx ["INSERT INTO event (event_id, event_type, stream_id,
                                             stream_version, occurred_at, data, metadata)
                          VALUES (?,?,?,
                                  (SELECT coalesce(max(stream_version),0)+1 FROM event
                                    WHERE stream_id = ?),
                                  ?, ?::jsonb, '{}'::jsonb)"
                         (random-uuid) "customer-notified"
                         #uuid "0f1c2b3a-0000-4000-8000-0000000000aa"
                         #uuid "0f1c2b3a-0000-4000-8000-0000000000aa"
                         (java.sql.Timestamp. (.getTime t0))
                         (str "{\"flavour\":\"" (get-in row [:payload :flavour]) "\"}")]))

(defn- notifications [ds]
  (:count (jdbc/execute-one! ds ["SELECT count(*) AS count FROM event
                                  WHERE event_type = 'customer-notified'"])))

;; ---------------------------------------------------------------------------
;; Across a boundary: at-least-once, unavoidably
;; ---------------------------------------------------------------------------

(deftest a-crash-between-publishing-and-marking-republishes-test
  (let [ds (fixture/datasource)
        published (atom [])]
    (sell-the-last-cone! ds)
    (testing "the relay publishes the first message and dies before marking it"
      (relay/relay-across-a-boundary! ds #(swap! published conj (:message-id %))
                                      (constantly true))
      (is (= 1 (count @published)))
      (is (= 2 (count (outbox/pending ds))) "nothing was marked sent"))
    (testing "on restart it publishes that message again"
      (relay/relay-across-a-boundary! ds #(swap! published conj (:message-id %)))
      (is (= 3 (count @published)) "two messages, three deliveries")
      (is (= 2 (count (distinct @published))) "of two distinct messages")
      (is (empty? (outbox/pending ds))))))

(deftest marking-first-would-lose-instead-test
  (testing "there is no ordering that is safe — only a choice of failure"
    (let [ds (fixture/datasource)]
      (sell-the-last-cone! ds)
      (jdbc/with-transaction [tx ds]
        (outbox/mark-sent! tx (:id (first (outbox/pending ds)))))
      (testing "a crash now loses the message entirely: nothing will retry it"
        (is (= 1 (count (outbox/pending ds))))))))

(deftest the-recipient-is-what-makes-it-safe-test
  (testing "at-least-once delivery plus an inbox is exactly-once processing"
    (let [ds (fixture/datasource)]
      (sell-the-last-cone! ds)
      (let [row     (first (outbox/pending ds))
            fact-id (parse-uuid (get-in row [:payload :fact-id]))
            deliver #(inbox/handle-once! ds :customer-app fact-id
                                         (fn [tx] (record-notification! tx row)))]
        (is (= :handled (deliver)))
        (is (= :already-handled (deliver)) "the redelivery is recognised")
        (is (= :already-handled (deliver)))
        (is (= 1 (notifications ds)) "one notification, three deliveries")))))

(deftest the-inbox-protects-an-effect-outside-the-read-model-test
  (testing "lab 12's :seen set lived in the model, so it guarded only the model"
    (let [ds (fixture/datasource)]
      (sell-the-last-cone! ds)
      (let [row     (first (outbox/pending ds))
            fact-id (parse-uuid (get-in row [:payload :fact-id]))]
        (inbox/handle-once! ds :customer-app fact-id
                            (fn [tx] (record-notification! tx row)))
        (testing "the record of having handled it and the effect are one write"
          (is (inbox/handled? ds :customer-app fact-id))
          (is (= 1 (notifications ds))))))))

(deftest two-recipients-each-handle-the-same-fact-once-test
  (testing "the inbox is keyed by recipient as well as fact"
    (let [ds (fixture/datasource)]
      (sell-the-last-cone! ds)
      (let [fact-id (parse-uuid (get-in (first (outbox/pending ds)) [:payload :fact-id]))]
        (is (= :handled (inbox/handle-once! ds :customer-app fact-id (fn [_] nil))))
        (is (= :handled (inbox/handle-once! ds :purchasing fact-id (fn [_] nil)))
            "a different module has not handled it")
        (is (= :already-handled (inbox/handle-once! ds :customer-app fact-id (fn [_] nil))))))))

;; ---------------------------------------------------------------------------
;; Inside one database: exactly-once delivery, which the network case cannot do
;; ---------------------------------------------------------------------------

(deftest one-database-means-one-transaction-test
  (let [ds (fixture/datasource)]
    (sell-the-last-cone! ds)
    (testing "outbox row, inbox row and effect all commit together"
      (let [moved (relay/relay-within-one-database!
                   ds {:customer-app record-notification!})]
        (is (= 2 (count moved)))
        (is (= [:handled :handled] (map second moved)))
        (is (empty? (outbox/pending ds)))
        (is (= 2 (count (inbox/entries ds))) "one per recipient")
        (is (= 1 (notifications ds)) "only the customer app notifies")))))

(deftest running-the-in-database-relay-twice-changes-nothing-test
  (let [ds (fixture/datasource)]
    (sell-the-last-cone! ds)
    (relay/relay-within-one-database! ds {:customer-app record-notification!})
    (let [again (relay/relay-within-one-database! ds {:customer-app record-notification!})]
      (is (empty? again) "nothing is pending, so nothing is moved")
      (is (= 1 (notifications ds))))))

(deftest there-is-no-window-to-crash-in-test
  (testing "the claim lab 12 could not make: exactly-once *delivery*"
    (let [ds (fixture/datasource)]
      (sell-the-last-cone! ds)
      (testing "a failure inside the move rolls back the whole thing"
        (is (thrown? Exception
                     (relay/relay-within-one-database!
                      ds {:customer-app (fn [_ _] (throw (ex-info "effect failed" {})))})))
        (testing "so nothing is marked sent and nothing is in the inbox"
          (is (= 2 (count (outbox/pending ds))))
          (is (empty? (inbox/entries ds)))
          (is (zero? (notifications ds)))))
      (testing "and a later successful run delivers exactly once"
        (relay/relay-within-one-database! ds {:customer-app record-notification!})
        (is (= 1 (notifications ds)))
        (is (empty? (outbox/pending ds)))))))
