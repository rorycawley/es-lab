(ns cart.adapter.driven.event-store-postgres-test
  "SPEC R6.4 — proves that when two requests act on the same cart at the same
   moment, exactly one may write."
  (:require [cart.adapter.driven.event-store-contract :as contract]
            [cart.adapter.driven.event-store-postgres :as pg]
            [cart.port.event-store :as store]
            [cart.test-db :as db]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.util.concurrent Callable CyclicBarrier Executors Future TimeUnit]))

(use-fixtures :once db/with-postgres)
(use-fixtures :each (fn [f] (db/truncate! db/*datasource*) (f)))

(def races 20)  ;; a race that happens not to interleave proves nothing

(defn- event [who]
  {:type :cart.event/product-item-added
   :data {:cart-id "c1"
          :product-item {:product-id who :quantity 1 :unit-price 1999}
          :added-at 1735689600000}})

(defn- race
  "Runs both thunks as simultaneously as the JVM allows.
   A thrown exception comes back as [:threw ex] rather than escaping."
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

(defn- race-n
  "Like race, but for n thunks released together. Used to show that the
   two-writer case is not a special case."
  [thunks]
  (let [barrier (CyclicBarrier. (count thunks))
        pool    (Executors/newFixedThreadPool (count thunks))]
    (try
      (->> thunks
           (mapv (fn [thunk]
                   (.submit pool ^Callable
                            (reify Callable
                              (call [_]
                                (.await barrier)
                                (try (thunk) (catch Exception e [:threw e])))))))
           (mapv #(.get ^Future % 30 TimeUnit/SECONDS)))
      (finally (.shutdownNow pool)))))

(defn- outcomes [results]
  {:ok       (filterv #(= :ok (first %)) results)
   :conflict (filterv #(= :conflict (first %)) results)
   :threw    (filterv #(= :threw (first %)) results)})

;; ---------------------------------------------------------------------------
;; Two appends to an existing stream — the UPDATE path
;; ---------------------------------------------------------------------------

(deftest two-appends-at-same-version-exactly-one-wins
  (dotimes [_ races]
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)]

      ;; seed: create the stream with one event, leaving it at version 1
      (store/append-to-stream es stream-id [(event "seed")] :stream-does-not-exist)

      (let [results (race #(store/append-to-stream es stream-id [(event "A")] 1)
                          #(store/append-to-stream es stream-id [(event "B")] 1))
            {:keys [ok conflict threw]} (outcomes results)]

        (testing "exactly one wins"
          (is (= 1 (count ok)))
          (is (= 1 (count conflict))))

        (testing "nobody crashed — a conflict is a result, not an exception"
          (is (empty? threw)
              (str "threw: " (mapv (comp ex-message second) threw))))

        (testing "the winner moved the stream to version 2"
          (is (= 2 (:version (second (first ok))))))

        (testing "the loser learns the version it actually is now (SPEC R4.5)"
          (is (= 2 (:current (second (first conflict)))))
          (is (= 1 (:expected (second (first conflict))))))

        (testing "only the winner's event was written"
          (is (= [1 2] (db/positions ds stream-id))))

        (testing "the streams row bumped once, not twice"
          (is (= 2 (db/stream-version ds stream-id))))))))

;; ---------------------------------------------------------------------------
;; Two creates of the same new stream — the INSERT path
;; ---------------------------------------------------------------------------

(deftest two-creates-exactly-one-wins
  (dotimes [_ races]
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)
          results   (race #(store/append-to-stream es stream-id [(event "A")]
                                                   :stream-does-not-exist)
                          #(store/append-to-stream es stream-id [(event "B")]
                                                   :stream-does-not-exist))
          {:keys [ok conflict threw]} (outcomes results)]

      (testing "exactly one wins"
        (is (= 1 (count ok)))
        (is (= 1 (count conflict))))

      (testing "ON CONFLICT DO NOTHING, not a unique-violation exception"
        (is (empty? threw)))

      (testing "the winner created the stream"
        (is (true? (:created-new-stream? (second (first ok))))))

      (testing "only one event exists"
        (is (= 1 (db/count-messages ds stream-id)))
        (is (= [1] (db/positions ds stream-id)))))))

;; ---------------------------------------------------------------------------
;; Three writers — R4.5's :current is a lower bound, not an equation
;; ---------------------------------------------------------------------------

(deftest three-appends-at-same-version-exactly-one-wins
  (testing "SPEC R4.4/R4.5 — two writers is not a special case"
    (dotimes [_ races]
      (let [ds        db/*datasource*
            es        (pg/make-store ds)
            stream-id (db/stream-id)]
        (store/append-to-stream es stream-id [(event "seed")] :stream-does-not-exist)

        (let [results (race-n [#(store/append-to-stream es stream-id [(event "A")] 1)
                               #(store/append-to-stream es stream-id [(event "B")] 1)
                               #(store/append-to-stream es stream-id [(event "C")] 1)])
              {:keys [ok conflict threw]} (outcomes results)]

          (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
          (is (= 1 (count ok)) "exactly one may write")
          (is (= 2 (count conflict)))

          (testing "only the winner's event exists"
            (is (= [1 2] (db/positions ds stream-id)))
            (is (= 2 (db/stream-version ds stream-id))))

          (testing "every loser's :current is a real committed version, never
                    stale and never invented"
            (doseq [[_ data] conflict]
              (is (= 1 (:expected data)))
              ;; R4.5: freshest committed at detection time. With three writers
              ;; that is >= the winner's version, and here the winner is the
              ;; only one who wrote, so it is exactly 2.
              (is (>= (:current data) 2))
              (is (= 2 (:current data))))))))))

;; ---------------------------------------------------------------------------
;; Multi-event appends reserve the whole range
;; ---------------------------------------------------------------------------

(deftest concurrent-multi-event-appends-do-not-interleave
  (dotimes [_ races]
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)]
      (store/append-to-stream es stream-id [(event "seed")] :stream-does-not-exist)

      (let [three (fn [who] [(event who) (event who) (event who)])
            results (race #(store/append-to-stream es stream-id (three "A") 1)
                          #(store/append-to-stream es stream-id (three "B") 1))
            {:keys [ok conflict]} (outcomes results)]

        (is (= 1 (count ok)))
        (is (= 1 (count conflict)))

        (testing "the winner's three events land contiguously, the loser writes none"
          (is (= [1 2 3 4] (db/positions ds stream-id)))
          (is (= 4 (:version (second (first ok))))))))))

;; ---------------------------------------------------------------------------
;; Expected version modes (SPEC R4.4)
;; ---------------------------------------------------------------------------

(deftest stream-does-not-exist-is-enforced
  (testing "creating over an existing stream must fail, not append"
    (let [es        (pg/make-store db/*datasource*)
          stream-id (db/stream-id)]
      (store/append-to-stream es stream-id [(event "first")] :stream-does-not-exist)
      (let [[outcome data] (store/append-to-stream es stream-id [(event "second")]
                                                   :stream-does-not-exist)]
        (is (= :conflict outcome))
        (is (= 1 (:current data)))
        (is (= 1 (db/count-messages db/*datasource* stream-id)))))))

(deftest any-version-skips-the-check
  (let [es        (pg/make-store db/*datasource*)
        stream-id (db/stream-id)]
    (store/append-to-stream es stream-id [(event "first")] :stream-does-not-exist)
    (let [[outcome data] (store/append-to-stream es stream-id [(event "second")] :any)]
      (is (= :ok outcome))
      (is (= 2 (:version data))))))

(deftest concurrent-any-appends-never-conflict
  (testing "SPEC R4.4 — :any opted out of the check, so contention must not
            manufacture a conflict. A SELECT-then-UPDATE implementation passes
            the sequential test above and fails this one."
    (dotimes [_ races]
      (let [ds        db/*datasource*
            es        (pg/make-store ds)
            stream-id (db/stream-id)]
        (store/append-to-stream es stream-id [(event "seed")] :stream-does-not-exist)

        (let [results (race #(store/append-to-stream es stream-id [(event "A")] :any)
                            #(store/append-to-stream es stream-id [(event "B")] :any))
              {:keys [ok conflict threw]} (outcomes results)]
          (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
          (is (= 2 (count ok)) "both must win")
          (is (empty? conflict))

          (testing "both writes landed, contiguously, one on top of the other"
            (is (= [1 2 3] (db/positions ds stream-id)))
            (is (= #{2 3} (set (map (comp :version second) ok))))
            (is (= 3 (db/stream-version ds stream-id)))))))))

(deftest concurrent-any-creates-never-conflict
  (testing "SPEC R4.4 — the upsert creates for one racer and appends for the
            other, rather than one losing"
    (dotimes [_ races]
      (let [ds        db/*datasource*
            es        (pg/make-store ds)
            stream-id (db/stream-id)
            results   (race #(store/append-to-stream es stream-id [(event "A")] :any)
                            #(store/append-to-stream es stream-id [(event "B")] :any))
            {:keys [ok conflict threw]} (outcomes results)]
        (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
        (is (= 2 (count ok)))
        (is (empty? conflict))
        (is (= [1 2] (db/positions ds stream-id)))

        (testing "exactly one of them reports having created the stream"
          (is (= 1 (count (filter (comp :created-new-stream? second) ok)))))))))

(deftest wrong-expected-version-conflicts
  (let [es        (pg/make-store db/*datasource*)
        stream-id (db/stream-id)]
    (store/append-to-stream es stream-id [(event "first")] :stream-does-not-exist)
    (let [[outcome data] (store/append-to-stream es stream-id [(event "second")] 7)]
      (is (= :conflict outcome))
      (is (= {:expected 7 :current 1} data)))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(deftest read-stream-reports-version-from-the-store
  (testing "SPEC R4.1 — version comes from the store, not from counting"
    (let [es        (pg/make-store db/*datasource*)
          stream-id (db/stream-id)]

      (is (= {:events [] :version 0 :exists? false}
             (store/read-stream es stream-id)))

      (store/append-to-stream es stream-id [(event "a") (event "b")]
                              :stream-does-not-exist)

      (let [{:keys [events version exists?]} (store/read-stream es stream-id)]
        (is (= 2 (count events)))
        (is (= 2 version))
        (is (true? exists?))
        (is (= :cart.event/product-item-added (:type (first events)))
            "event type is reconstructed from message_type")))))

(deftest events-round-trip-through-postgres
  (testing "SPEC R5.1 — JSONB preserves the event payload shape"
    (let [es        (pg/make-store db/*datasource*)
          stream-id (db/stream-id)
          original  (event "shoes")]
      (store/append-to-stream es stream-id [original] :stream-does-not-exist)
      (is (= original (first (:events (store/read-stream es stream-id))))))))

(deftest event-data-is-stored-as-queryable-jsonb
  (testing "SPEC R5.1 — payload fields are visible to Postgres JSONB operators"
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)]
      (store/append-to-stream es stream-id [(event "shoes")] :stream-does-not-exist)

      (let [row (jdbc/execute-one!
                 ds
                 ["SELECT message_data ->> 'cart-id' AS cart_id,
                          message_data #>> '{product-item,product-id}' AS product_id,
                          (message_data #>> '{product-item,unit-price}')::int AS unit_price
                     FROM messages
                    WHERE stream_id = ? AND stream_position = 1"
                  stream-id]
                 {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= {:cart_id "c1" :product_id "shoes" :unit_price 1999}
               row))))))

(deftest app-role-can-use-store-without-ddl-or-table-writes
  (testing "migrations run separately; the runtime role can use the port but
            cannot mutate schema or tables directly"
    (let [ds        (db/app-datasource)
          es        (pg/make-store ds)
          stream-id (db/stream-id)]
      (try
        (let [[outcome data] (store/append-to-stream es stream-id [(event "shoes")]
                                                     :stream-does-not-exist)]
          (is (= :ok outcome))
          (is (= 1 (:version data))))

        (is (= 1 (:version (store/read-stream es stream-id))))

        (is (thrown-with-msg?
             Exception #"permission denied"
             (jdbc/execute! ds ["INSERT INTO streams
                                    (stream_id, stream_type, stream_position)
                                  VALUES ('direct', 'shopping_cart', 1)"])))

        (is (thrown-with-msg?
             Exception #"permission denied"
             (jdbc/execute! ds ["UPDATE streams
                                    SET stream_position = stream_position + 1
                                  WHERE stream_id = ?"
                                stream-id])))

        (is (thrown-with-msg?
             Exception #"permission denied"
             (jdbc/execute! ds ["CREATE TABLE app_role_should_not_create (id int)"])))
        (finally
          (.close ds))))))

(deftest corrupt-stored-events-are-rejected
  (testing "SPEC R5.2 — storage corruption is loud, never handed to fold"
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)]
      (jdbc/execute-one! ds ["INSERT INTO streams
                                  (stream_id, stream_type, stream_position)
                               VALUES (?, 'shopping_cart', 1)"
                             stream-id])
      (jdbc/execute-one! ds ["INSERT INTO messages
                                  (stream_id, stream_position, message_id,
                                   message_type, message_data, message_metadata)
                               VALUES (?, 1, ?, 'cart.event/product-item-added',
                                       '{}'::jsonb, '{}'::jsonb)"
                             stream-id (random-uuid)])

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Corrupt event in stream"
                            (store/read-stream es stream-id))))))

;; ---------------------------------------------------------------------------
;; message_metadata
;; ---------------------------------------------------------------------------

(deftest event-metadata-round-trips
  (testing "metadata reaches the column and comes back as JSON-compatible data"
    (let [ds        db/*datasource*
          es        (pg/make-store ds)
          stream-id (db/stream-id)
          original  (assoc (event "shoes")
                           :metadata {:now 1735689600000
                                      :source "web"})]
      (store/append-to-stream es stream-id [original] :stream-does-not-exist)

      (is (= original (first (:events (store/read-stream es stream-id))))
          "plain JSONB keeps JSON-compatible metadata stable")

      (testing "the column is genuinely populated, not the default"
        (is (match? (m/via #(json/parse-string % true)
                           {:now 1735689600000
                            :source "web"})
                    (db/metadata-json ds stream-id 1))))

      (testing "metadata is queryable through JSONB operators"
        (is (= "web"
               (:source (jdbc/execute-one!
                         ds
                         ["SELECT message_metadata ->> 'source' AS source
                             FROM messages
                            WHERE stream_id = ? AND stream_position = 1"
                          stream-id]
                         {:builder-fn rs/as-unqualified-lower-maps}))))))))

(deftest an-event-without-metadata-reads-back-without-it
  (testing "the key is absent, not {} — otherwise every round trip would drift"
    (let [es        (pg/make-store db/*datasource*)
          stream-id (db/stream-id)]
      (store/append-to-stream es stream-id [(event "shoes")] :stream-does-not-exist)
      (is (not (contains? (first (:events (store/read-stream es stream-id)))
                          :metadata))))))

;; ---------------------------------------------------------------------------
;; Shared contract (SPEC R6.5)
;; ---------------------------------------------------------------------------

(deftest satisfies-the-event-store-contract
  (contract/verify #(pg/make-store db/*datasource*) db/stream-id))
