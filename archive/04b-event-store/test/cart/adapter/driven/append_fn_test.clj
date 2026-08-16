(ns cart.adapter.driven.append-fn-test
  "Tests the SQL function `append_to_stream` directly, below the port.

   The port-level tests in event-store-postgres-test cover the same races
   through the EventStore protocol. Having both means a failure tells you
   immediately whether the fault is in the SQL or in the Clojure marshalling."
  (:require [cart.test-db :as db]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Connection]
           [java.util UUID]
           [java.util.concurrent Callable CyclicBarrier Executors Future TimeUnit]))

(use-fixtures :once db/with-postgres)
(use-fixtures :each (fn [f] (db/truncate! db/*datasource*) (f)))

(def races 20)

;; ---------------------------------------------------------------------------
;; Calling the function
;; ---------------------------------------------------------------------------

(def ^:private append-sql
  "SELECT * FROM append_to_stream(?, ?, ?, ?, ?::uuid[], ?::text[], ?::jsonb[], ?::jsonb[])")

(defn- ->text-array [^Connection conn coll]
  (.createArrayOf conn "text" (into-array String coll)))

(defn- ->uuid-array [^Connection conn coll]
  (.createArrayOf conn "uuid" (into-array UUID coll)))

(defn append-on!
  "Calls the SQL function on a connection or transaction the caller already
   owns — used to prove a losing write leaves that transaction usable (R4.3)."
  [conn stream-id expected require-new events]
  (let [n (count events)]
    (jdbc/execute-one!
     conn
     [append-sql
      stream-id "shopping_cart" expected require-new
      (->uuid-array conn (repeatedly n #(UUID/randomUUID)))
      (->text-array conn (map :type events))
      (->text-array conn (map :data events))
      (->text-array conn (repeat n "{}"))]
     {:builder-fn rs/as-unqualified-lower-maps})))

(defn append!
  "Calls the SQL function directly.
   expected: a number, or nil for no check. require-new: boolean.
   events: [{:type \"item_added\" :data \"{\\\"who\\\":\\\"A\\\"}\"}]

   Each call takes its OWN connection. Two calls sharing one connection are
   serialised by the driver, so the race would prove nothing."
  ([ds stream-id expected events] (append! ds stream-id expected false events))
  ([ds stream-id expected require-new events]
   (with-open [conn (jdbc/get-connection ds)]
     (append-on! conn stream-id expected require-new events))))

;; ---------------------------------------------------------------------------
;; Racing
;; ---------------------------------------------------------------------------

(defn race
  "Runs both thunks as simultaneously as the JVM allows. A thrown exception is
   captured as [:threw ex] rather than escaping, so the caller can tell a crash
   apart from a clean loss."
  [f g]
  (let [barrier (CyclicBarrier. 2)
        pool    (Executors/newFixedThreadPool 2)
        guard   (fn [thunk]
                  (reify Callable
                    (call [_]
                      (.await barrier)
                      (try (thunk) (catch Exception e [:threw e])))))]
    (try
      (let [a (.submit pool ^Callable (guard f))
            b (.submit pool ^Callable (guard g))]
        [(.get ^Future a 30 TimeUnit/SECONDS)
         (.get ^Future b 30 TimeUnit/SECONDS)])
      (finally (.shutdownNow pool)))))

(defn- outcomes
  "Three buckets, not two. `(remove :success results)` would count a crashed
   thread as a legitimate loser, and hide exactly the behaviour we care about:
   a conflict must be a returned value, never an exception, so that a losing
   write cannot abort the caller's transaction."
  [results]
  {:won   (filterv #(and (map? %) (:success %)) results)
   :lost  (filterv #(and (map? %) (not (:success %))) results)
   :threw (filterv #(and (vector? %) (= :threw (first %))) results)})

(defn- fail-messages [threw]
  (mapv (comp ex-message second) threw))

;; ---------------------------------------------------------------------------
;; Helpers for asserting what actually landed
;; ---------------------------------------------------------------------------

(defn- count-rows [ds table stream-id]
  (:count (jdbc/execute-one! ds [(str "SELECT count(*) AS count FROM " table
                                      " WHERE stream_id = ?")
                                 stream-id]
                             {:builder-fn rs/as-unqualified-lower-maps})))

(defn- rows [ds stream-id]
  (jdbc/execute! ds ["SELECT stream_position, message_data
                        FROM messages WHERE stream_id = ?
                       ORDER BY stream_position" stream-id]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- who-at
  "The \"who\" field of the event stored at a given position."
  [ds stream-id position]
  (some->> (rows ds stream-id)
           (filter #(= position (:stream_position %)))
           first
           :message_data
           str
           (re-find #"\"who\"\s*:\s*\"([^\"]+)\"")
           second))

;; ---------------------------------------------------------------------------
;; Two appends to an existing stream — the UPDATE path
;; ---------------------------------------------------------------------------

(deftest concurrent-appends
  (dotimes [_ races]
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]

      ;; seed: create the stream, leaving it at version 1
      (append! ds stream-id 0 [{:type "created" :data "{}"}])

      (let [results (race #(append! ds stream-id 1 [{:type "item_added" :data "{\"who\":\"A\"}"}])
                          #(append! ds stream-id 1 [{:type "item_added" :data "{\"who\":\"B\"}"}]))
            {:keys [won lost threw]} (outcomes results)]

        (testing "a conflict is a value, not an exception"
          (is (empty? threw) (str "threw: " (fail-messages threw))))

        (testing "exactly one append succeeds"
          (is (= 1 (count won)))
          (is (= 1 (count lost))))

        (testing "winner advances the stream to version 2"
          (is (= 2 (:next_position (first won)))))

        (testing "loser is told the version it actually is now"
          (is (= 2 (:current_position (first lost))))
          (is (nil? (:next_position (first lost)))))

        (testing "only two events exist, at contiguous positions"
          (is (= 2 (count-rows ds "messages" stream-id)))
          (is (= [1 2] (mapv :stream_position (rows ds stream-id)))))

        (testing "position 2 holds the winner's data, not the loser's"
          ;; count alone would pass even if the loser's row had replaced the
          ;; winner's, so check whose payload actually landed
          (is (contains? #{"A" "B"} (who-at ds stream-id 2))))

        (testing "the streams row bumped once"
          (is (= 1 (count-rows ds "streams" stream-id)))
          (is (= 2 (db/stream-version ds stream-id))))))))

;; ---------------------------------------------------------------------------
;; Two creates of the same stream — the INSERT path
;; ---------------------------------------------------------------------------

(deftest concurrent-creates
  (dotimes [_ races]
    (let [ds        db/*datasource*
          stream-id (db/stream-id)
          results   (race #(append! ds stream-id 0 [{:type "created" :data "{\"who\":\"A\"}"}])
                          #(append! ds stream-id 0 [{:type "created" :data "{\"who\":\"B\"}"}]))
          {:keys [won lost threw]} (outcomes results)]

      (testing "ON CONFLICT DO NOTHING, not a unique-violation exception"
        (is (empty? threw) (str "threw: " (fail-messages threw))))

      (testing "exactly one creation succeeds"
        (is (= 1 (count won)))
        (is (= 1 (count lost))))

      (testing "stream exists exactly once"
        (is (= 1 (count-rows ds "streams" stream-id))))

      (testing "exactly one created event exists"
        (is (= 1 (count-rows ds "messages" stream-id)))
        (is (= [1] (mapv :stream_position (rows ds stream-id)))))

      (testing "loser is told the stream is now at version 1"
        (is (= 1 (:current_position (first lost))))))))

;; ---------------------------------------------------------------------------
;; Multi-event appends reserve the whole range before writing
;; ---------------------------------------------------------------------------

(deftest concurrent-multi-event-appends-do-not-interleave
  (dotimes [_ races]
    (let [ds        db/*datasource*
          stream-id (db/stream-id)
          three     (fn [who] (repeat 3 {:type "item_added"
                                         :data (str "{\"who\":\"" who "\"}")}))]
      (append! ds stream-id 0 [{:type "created" :data "{}"}])

      (let [results (race #(append! ds stream-id 1 (three "A"))
                          #(append! ds stream-id 1 (three "B")))
            {:keys [won lost threw]} (outcomes results)]

        (is (empty? threw) (str "threw: " (fail-messages threw)))
        (is (= 1 (count won)))
        (is (= 1 (count lost)))

        (testing "the winner's three land contiguously; the loser writes none"
          (is (= [1 2 3 4] (mapv :stream_position (rows ds stream-id))))
          (is (= 4 (:next_position (first won)))))

        (testing "all three winning events came from the same writer"
          (let [whos (->> [2 3 4] (map #(who-at ds stream-id %)) set)]
            (is (= 1 (count whos))
                (str "interleaved writers: " whos))))))))

;; ---------------------------------------------------------------------------
;; The expected-version modes, at function level
;; ---------------------------------------------------------------------------

(deftest require-new-is-enforced
  (testing "creating over an existing stream fails rather than appending"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (append! ds stream-id nil true [{:type "created" :data "{}"}])
      (let [result (append! ds stream-id nil true [{:type "created" :data "{}"}])]
        (is (false? (:success result)))
        (is (= 1 (:current_position result)))
        (is (= 1 (count-rows ds "messages" stream-id)))))))

(deftest null-expected-skips-the-check
  (let [ds        db/*datasource*
        stream-id (db/stream-id)]
    (append! ds stream-id 0 [{:type "created" :data "{}"}])
    (let [result (append! ds stream-id nil [{:type "item_added" :data "{}"}])]
      (is (true? (:success result)))
      (is (= 2 (:next_position result))))))

(deftest wrong-expected-version-fails-without-writing
  (let [ds        db/*datasource*
        stream-id (db/stream-id)]
    (append! ds stream-id 0 [{:type "created" :data "{}"}])
    (let [result (append! ds stream-id 7 [{:type "item_added" :data "{}"}])]
      (is (false? (:success result)))
      (is (= 1 (:current_position result)))
      (is (= 1 (count-rows ds "messages" stream-id))))))

(deftest empty-message-list-is-rejected
  (testing "the caller skips the write when a decision is empty, so reaching
            the function with nothing is a bug and should be loud"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (is (thrown? Exception (append! ds stream-id 0 []))))))

;; ---------------------------------------------------------------------------
;; R4.3 — a losing write must not abort the caller's transaction
;; ---------------------------------------------------------------------------

(deftest losing-append-does-not-poison-the-callers-transaction
  (testing "returning success = FALSE rather than raising is what lets other
            legitimate work in the same transaction survive a lost race"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (append! ds stream-id 0 [{:type "created" :data "{}"}])

      (jdbc/with-transaction [tx ds]
        ;; unrelated work in flight, in the same transaction as the loss
        (jdbc/execute-one! tx ["CREATE TEMP TABLE in_flight (v int) ON COMMIT DROP"])
        (jdbc/execute-one! tx ["INSERT INTO in_flight VALUES (42)"])

        (let [lost (append-on! tx stream-id 7 false [{:type "item_added" :data "{}"}])]
          (is (false? (:success lost)))
          (is (= 1 (:current_position lost))))

        (testing "the transaction is still alive after the loss"
          (is (= 42 (:v (jdbc/execute-one! tx ["SELECT v FROM in_flight"]
                                           {:builder-fn rs/as-unqualified-lower-maps})))
              "an aborted transaction would raise 25P02 here"))

        (testing "and can still do real work"
          (let [won (append-on! tx stream-id 1 false [{:type "item_added" :data "{}"}])]
            (is (true? (:success won)))
            (is (= 2 (:next_position won))))))

      (testing "the work committed"
        (is (= [1 2] (db/positions ds stream-id)))))))

;; ---------------------------------------------------------------------------
;; R4.9 — isolation guard
;; ---------------------------------------------------------------------------

(deftest rejects-non-read-committed-isolation
  (testing "at repeatable read a losing UPDATE raises 40001 instead of matching
            zero rows, so the function must refuse to run there"
    (let [stream-id (db/stream-id)]
      (is (thrown-with-msg?
           Exception #"read committed"
           (jdbc/with-transaction [tx db/*datasource* {:isolation :repeatable-read}]
             (jdbc/execute-one!
              tx
              [append-sql stream-id "shopping_cart" nil true
               (->uuid-array tx [(UUID/randomUUID)])
               (->text-array tx ["created"])
               (->text-array tx ["{}"])
               (->text-array tx ["{}"])]
              {:builder-fn rs/as-unqualified-lower-maps})))))))

;; ---------------------------------------------------------------------------
;; R4.10 — input validation
;; ---------------------------------------------------------------------------

(deftest rejects-mismatched-array-lengths
  (testing "unnest pads short arrays with NULL, which would write more events
            than the version reserved"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (with-open [conn (jdbc/get-connection ds)]
        (is (thrown-with-msg?
             Exception #"differ in length"
             (jdbc/execute-one!
              conn
              [append-sql stream-id "shopping_cart" nil true
               (->uuid-array conn [(UUID/randomUUID)])          ; 1 id
               (->text-array conn ["a" "b"])                   ; 2 types
               (->text-array conn ["{}" "{}"])                 ; 2 data
               (->text-array conn ["{}" "{}"])]                ; 2 meta
              {:builder-fn rs/as-unqualified-lower-maps}))))
      (is (= 0 (count-rows ds "messages" stream-id))))))

(deftest rejects-invalid-mode-arguments
  (testing "the two-parameter expected-version encoding has no ambiguous modes"
    (let [ds db/*datasource*]
      (doseq [[stream-id expected require-new pattern]
              [[(db/stream-id) nil nil #"p_require_new"]
               [(db/stream-id) -1 false #"expected version"]
               [(db/stream-id) 0 true #"p_expected must be NULL"]]]
        (is (thrown-with-msg?
             Exception pattern
             (append! ds stream-id expected require-new [{:type "a" :data "{}"}])))
        (is (= 0 (count-rows ds "streams" stream-id)))
        (is (= 0 (count-rows ds "messages" stream-id)))))))

(deftest rejects-multidimensional-arrays
  (testing "unnest flattens multidimensional arrays, so they must be rejected"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (is (thrown-with-msg?
           Exception #"one-dimensional"
           (jdbc/execute-one!
            ds
            ["SELECT * FROM append_to_stream(
                  ?, 'shopping_cart', NULL, FALSE,
                  ARRAY[[?::uuid, ?::uuid]],
                  ARRAY[['a', 'b']],
                  ARRAY[['{}'::jsonb, '{}'::jsonb]],
                  ARRAY[['{}'::jsonb, '{}'::jsonb]])"
             stream-id (str (UUID/randomUUID)) (str (UUID/randomUUID))]
            {:builder-fn rs/as-unqualified-lower-maps})))
      (is (= 0 (count-rows ds "streams" stream-id)))
      (is (= 0 (count-rows ds "messages" stream-id))))))

(deftest rejects-null-array-elements
  (testing "same-length arrays with NULL elements must not claim a version first"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (with-open [conn (jdbc/get-connection ds)]
        (is (thrown-with-msg?
             Exception #"NULL elements"
             (jdbc/execute-one!
              conn
              [append-sql stream-id "shopping_cart" nil false
               (->uuid-array conn [(UUID/randomUUID)])
               (->text-array conn ["a"])
               (->text-array conn [nil])
               (->text-array conn ["{}"])]
              {:builder-fn rs/as-unqualified-lower-maps}))))
      (is (= 0 (count-rows ds "streams" stream-id)))
      (is (= 0 (count-rows ds "messages" stream-id))))))

(deftest rejects-duplicate-message-ids-within-an-append
  (testing "the unique table constraint is a backstop; the function rejects first"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)
          message-id (UUID/randomUUID)]
      (with-open [conn (jdbc/get-connection ds)]
        (is (thrown-with-msg?
             Exception #"message ids must be unique"
             (jdbc/execute-one!
              conn
              [append-sql stream-id "shopping_cart" nil false
               (->uuid-array conn [message-id message-id])
               (->text-array conn ["a" "b"])
               (->text-array conn ["{}" "{}"])
               (->text-array conn ["{}" "{}"])]
              {:builder-fn rs/as-unqualified-lower-maps}))))
      (is (= 0 (count-rows ds "streams" stream-id)))
      (is (= 0 (count-rows ds "messages" stream-id))))))

(deftest rejects-non-object-jsonb
  (testing "event data and metadata are JSON objects, not arbitrary JSON values"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (is (thrown-with-msg?
           Exception #"JSON objects"
           (append! ds stream-id nil false [{:type "a" :data "[]"}])))
      (is (= 0 (count-rows ds "streams" stream-id)))
      (is (= 0 (count-rows ds "messages" stream-id))))))

(deftest ddl-backstops-reject-impossible-state
  (testing "constraints make bypassing append_to_stream fail loudly"
    (let [ds         db/*datasource*
          stream-id  (db/stream-id)
          message-id (UUID/randomUUID)]
      (is (thrown-with-msg?
           Exception #"streams_stream_position_positive"
           (jdbc/execute-one! ds ["INSERT INTO streams
                                      (stream_id, stream_type, stream_position)
                                   VALUES (?, 'shopping_cart', 0)"
                                  stream-id])))

      (is (thrown-with-msg?
           Exception #"messages_stream_fk"
           (jdbc/execute-one! ds ["INSERT INTO messages
                                      (stream_id, stream_position, message_id,
                                       message_type, message_data, message_metadata)
                                   VALUES (?, 1, ?, 'a', '{}'::jsonb, '{}'::jsonb)"
                                  stream-id (UUID/randomUUID)])))

      (jdbc/execute-one! ds ["INSERT INTO streams
                                 (stream_id, stream_type, stream_position)
                              VALUES (?, 'shopping_cart', 1)"
                             stream-id])

      (is (thrown-with-msg?
           Exception #"messages_message_data_object"
           (jdbc/execute-one! ds ["INSERT INTO messages
                                      (stream_id, stream_position, message_id,
                                       message_type, message_data, message_metadata)
                                   VALUES (?, 1, ?, 'a', '[]'::jsonb, '{}'::jsonb)"
                                  stream-id (UUID/randomUUID)])))

      (is (thrown-with-msg?
           Exception #"messages_message_metadata_object"
           (jdbc/execute-one! ds ["INSERT INTO messages
                                      (stream_id, stream_position, message_id,
                                       message_type, message_data, message_metadata)
                                   VALUES (?, 1, ?, 'a', '{}'::jsonb, '[]'::jsonb)"
                                  stream-id (UUID/randomUUID)])))

      (jdbc/execute-one! ds ["INSERT INTO messages
                                 (stream_id, stream_position, message_id,
                                  message_type, message_data, message_metadata)
                              VALUES (?, 1, ?, 'a', '{}'::jsonb, '{}'::jsonb)"
                             stream-id message-id])

      (is (thrown-with-msg?
           Exception #"messages_message_id_unique"
           (jdbc/execute-one! ds ["INSERT INTO messages
                                      (stream_id, stream_position, message_id,
                                       message_type, message_data, message_metadata)
                                   VALUES (?, 2, ?, 'b', '{}'::jsonb, '{}'::jsonb)"
                                  stream-id message-id]))))))

;; ---------------------------------------------------------------------------
;; R4.4 — :any must never conflict, even under contention
;; ---------------------------------------------------------------------------

(deftest concurrent-any-appends-both-succeed
  (dotimes [_ races]
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (append! ds stream-id 0 [{:type "created" :data "{}"}])

      (let [results (race #(append! ds stream-id nil [{:type "item_added" :data "{\"who\":\"A\"}"}])
                          #(append! ds stream-id nil [{:type "item_added" :data "{\"who\":\"B\"}"}]))
            {:keys [won lost threw]} (outcomes results)]

        (testing "no concurrency check means no conflict, ever"
          (is (empty? threw) (str "threw: " (fail-messages threw)))
          (is (= 2 (count won)))
          (is (= 0 (count lost))))

        (testing "both writes landed, at distinct contiguous positions"
          (is (= [1 2 3] (mapv :stream_position (rows ds stream-id))))
          (is (= #{2 3} (set (map :next_position won)))))

        (testing "the streams row agrees with the messages written"
          (is (= 3 (db/stream-version ds stream-id))))))))

(deftest concurrent-any-creates-both-succeed
  (dotimes [_ races]
    (let [ds        db/*datasource*
          stream-id (db/stream-id)
          results   (race #(append! ds stream-id nil [{:type "created" :data "{\"who\":\"A\"}"}])
                          #(append! ds stream-id nil [{:type "created" :data "{\"who\":\"B\"}"}]))
          {:keys [won lost threw]} (outcomes results)]

      (is (empty? threw) (str "threw: " (fail-messages threw)))
      (is (= 2 (count won)) "the upsert creates for one and appends for the other")
      (is (= 0 (count lost)))
      (is (= [1 2] (mapv :stream_position (rows ds stream-id)))))))

;; ---------------------------------------------------------------------------
;; The two version sources must never disagree
;; ---------------------------------------------------------------------------

(deftest streams-version-always-equals-max-message-position
  (testing "reads take the version from messages, writes check it against
            streams — the invariant that makes that safe"
    (let [ds        db/*datasource*
          stream-id (db/stream-id)]
      (append! ds stream-id 0 [{:type "a" :data "{}"}])
      (append! ds stream-id 1 [{:type "b" :data "{}"} {:type "c" :data "{}"}])
      (append! ds stream-id nil [{:type "d" :data "{}"}])
      (append! ds stream-id 99 [{:type "nope" :data "{}"}])   ; conflicts, writes nothing

      (let [max-pos (apply max (mapv :stream_position (rows ds stream-id)))]
        (is (= 4 max-pos))
        (is (= 4 (db/stream-version ds stream-id)))))))
