(ns cart.adapter.driven.event-store-sqlite-test
  "SQLite adapter tests.

   SQLite serializes writers, so these races prove our BEGIN IMMEDIATE critical
   section returns the same EventStore port semantics as Postgres."
  (:require [cart.adapter.driven.event-store-contract :as contract]
            [cart.adapter.driven.event-store-sqlite :as sqlite]
            [cart.port.event-store :as store]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.util.concurrent Callable CyclicBarrier Executors Future TimeUnit]))

(def ^:dynamic *datasource* nil)

(def races 20)

(defn- jdbc-url []
  (str "jdbc:sqlite:target/sqlite-test/" (random-uuid) ".sqlite3"))

(defn- with-sqlite [f]
  (let [ds (sqlite/make-datasource {:jdbc-url (jdbc-url)
                                    :pool-size 8
                                    :busy-timeout-ms 10000})]
    (try
      (binding [*datasource* ds]
        (f))
      (finally
        (.close ds)))))

(use-fixtures :each with-sqlite)

(defn- make-store []
  (sqlite/make-store *datasource* {:busy-timeout-ms 10000}))

(defn- stream-id []
  (str "shopping_cart-" (random-uuid)))

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

(defn- outcomes [results]
  {:ok       (filterv #(= :ok (first %)) results)
   :conflict (filterv #(= :conflict (first %)) results)
   :threw    (filterv #(= :threw (first %)) results)})

(defn- count-messages [stream-id]
  (:count (jdbc/execute-one! *datasource*
                             ["SELECT count(*) AS count
                                 FROM messages
                                WHERE stream_id = ?"
                              stream-id]
                             {:builder-fn rs/as-unqualified-lower-maps})))

(defn- positions [stream-id]
  (mapv :stream_position
        (jdbc/execute! *datasource*
                       ["SELECT stream_position
                           FROM messages
                          WHERE stream_id = ?
                          ORDER BY stream_position"
                        stream-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- stream-version [stream-id]
  (:stream_position
   (jdbc/execute-one! *datasource*
                      ["SELECT stream_position FROM streams WHERE stream_id = ?"
                       stream-id]
                      {:builder-fn rs/as-unqualified-lower-maps})))

(deftest satisfies-the-event-store-contract
  (contract/verify make-store stream-id))

(deftest two-appends-at-same-version-exactly-one-wins
  (dotimes [_ races]
    (let [es        (make-store)
          id        (stream-id)]
      (store/append-to-stream es id [(event "seed")] :stream-does-not-exist)

      (let [results (race #(store/append-to-stream es id [(event "A")] 1)
                          #(store/append-to-stream es id [(event "B")] 1))
            {:keys [ok conflict threw]} (outcomes results)]
        (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
        (is (= 1 (count ok)))
        (is (= 1 (count conflict)))
        (is (= 2 (:version (second (first ok)))))
        (is (= {:expected 1 :current 2} (second (first conflict))))
        (is (= [1 2] (positions id)))
        (is (= 2 (stream-version id)))))))

(deftest two-creates-exactly-one-wins
  (dotimes [_ races]
    (let [es        (make-store)
          id        (stream-id)
          results   (race #(store/append-to-stream es id [(event "A")]
                                                   :stream-does-not-exist)
                          #(store/append-to-stream es id [(event "B")]
                                                   :stream-does-not-exist))
          {:keys [ok conflict threw]} (outcomes results)]
      (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
      (is (= 1 (count ok)))
      (is (= 1 (count conflict)))
      (is (true? (:created-new-stream? (second (first ok)))))
      (is (= 1 (:current (second (first conflict)))))
      (is (= [1] (positions id)))
      (is (= 1 (count-messages id))))))

(deftest concurrent-multi-event-appends-do-not-interleave
  (dotimes [_ races]
    (let [es    (make-store)
          id    (stream-id)
          three (fn [who] [(event who) (event who) (event who)])]
      (store/append-to-stream es id [(event "seed")] :stream-does-not-exist)

      (let [results (race #(store/append-to-stream es id (three "A") 1)
                          #(store/append-to-stream es id (three "B") 1))
            {:keys [ok conflict threw]} (outcomes results)]
        (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
        (is (= 1 (count ok)))
        (is (= 1 (count conflict)))
        (is (= [1 2 3 4] (positions id)))
        (is (= 4 (:version (second (first ok)))))
        (is (= 4 (stream-version id)))))))

(deftest concurrent-any-appends-never-conflict
  (dotimes [_ races]
    (let [es (make-store)
          id (stream-id)]
      (store/append-to-stream es id [(event "seed")] :stream-does-not-exist)

      (let [results (race #(store/append-to-stream es id [(event "A")] :any)
                          #(store/append-to-stream es id [(event "B")] :any))
            {:keys [ok conflict threw]} (outcomes results)]
        (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
        (is (= 2 (count ok)))
        (is (empty? conflict))
        (is (= [1 2 3] (positions id)))
        (is (= #{2 3} (set (map (comp :version second) ok))))
        (is (= 3 (stream-version id)))))))

(deftest concurrent-any-creates-never-conflict
  (dotimes [_ races]
    (let [es      (make-store)
          id      (stream-id)
          results (race #(store/append-to-stream es id [(event "A")] :any)
                        #(store/append-to-stream es id [(event "B")] :any))
          {:keys [ok conflict threw]} (outcomes results)]
      (is (empty? threw) (str "threw: " (mapv (comp ex-message second) threw)))
      (is (= 2 (count ok)))
      (is (empty? conflict))
      (is (= [1 2] (positions id)))
      (is (= 1 (count (filter (comp :created-new-stream? second) ok)))))))

(deftest english-chinese-and-arabic-events-round-trip-through-sqlite
  (testing "UTF-8 survives text columns and SQLite JSON operators"
    (let [es         (make-store)
          cart-id    "cart-English-购物车-عربة"
          product-id "tea-茶-شاي"
          id         (str "shopping_cart-" cart-id)
          original   {:type :cart.event/product-item-added
                      :data {:cart-id cart-id
                             :product-item {:product-id product-id
                                            :quantity 1
                                            :unit-price 1999}
                             :added-at 1735689600000}
                      :metadata {:now 1735689600000
                                 :source "web-English-来源-مصدر"}}]
      (store/append-to-stream es id [original] :stream-does-not-exist)

      (is (= original (first (:events (store/read-stream es id)))))
      (is (= {:product_id product-id
              :source "web-English-来源-مصدر"}
             (jdbc/execute-one!
              *datasource*
              ["SELECT json_extract(message_data,
                                    '$.\"product-item\".\"product-id\"') AS product_id,
                       json_extract(message_metadata, '$.source') AS source
                  FROM messages
                 WHERE stream_id = ?"
               id]
              {:builder-fn rs/as-unqualified-lower-maps}))))))

(deftest sqlite-pragmas-and-ddl-constraints-are-active
  (let [settings (jdbc/execute-one! *datasource*
                                    ["SELECT
                                         (SELECT encoding FROM pragma_encoding) AS encoding,
                                         (SELECT journal_mode FROM pragma_journal_mode) AS journal_mode,
                                         (SELECT foreign_keys FROM pragma_foreign_keys) AS foreign_keys"]
                                    {:builder-fn rs/as-unqualified-lower-maps})]
    (is (= {:encoding "UTF-8"
            :journal_mode "wal"
            :foreign_keys 1}
           settings)))

  (testing "orphan messages are rejected"
    (is (thrown? Exception
                 (jdbc/execute-one!
                  *datasource*
                  ["INSERT INTO messages
                      (stream_id, stream_position, message_id, message_type,
                       message_data, message_metadata)
                    VALUES (?, 1, ?, ?, '{}', '{}')"
                   (stream-id) (str (random-uuid)) "cart.event/confirmed"]))))

  (testing "non-object JSON is rejected"
    (let [id (stream-id)]
      (jdbc/execute-one! *datasource*
                         ["INSERT INTO streams
                             (stream_id, stream_type, stream_position)
                           VALUES (?, 'shopping_cart', 1)"
                          id])
      (is (thrown? Exception
                   (jdbc/execute-one!
                    *datasource*
                    ["INSERT INTO messages
                        (stream_id, stream_position, message_id, message_type,
                         message_data, message_metadata)
                      VALUES (?, 1, ?, ?, '[]', '{}')"
                     id (str (random-uuid)) "cart.event/confirmed"])))
      (is (= 0 (count-messages id))))))
