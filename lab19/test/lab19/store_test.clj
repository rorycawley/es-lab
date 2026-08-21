(ns lab19.store-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [lab19.application :as application]
            [lab19.fixture :as fixture]
            [lab19.store :as store]
            [lab19.truck :as truck]))

(use-fixtures :each fixture/with-store)

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- handle
  "The public command use case, against Postgres."
  [ds cmd]
  (application/handle ds truck-1 cmd gen-id t0))

(defn- identified
  [event-id cmd event]
  (-> event
      (assoc :event/id event-id
             :event/occurred-at t0)
      (update :metadata assoc
              :causation-id (:command/id cmd)
              :correlation-id (:correlation-id cmd))))

;; ---------------------------------------------------------------------------
;; The domain does not know
;; ---------------------------------------------------------------------------

(deftest lab8s-domain-model-runs-against-postgres-test
  (let [ds (fixture/datasource)]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 1}))
    (handle ds (command :buy-flavour {:flavour "vanilla"}))
    (testing "selling the last cone still produces two events (lab 5)"
      (is (= [:truck-loaded :flavour-sold :stock-depleted]
             (map :event/type (store/stream ds truck-1)))))
    (testing "and the fold still answers"
      (is (= {"vanilla" 0} (truck/replay (store/stream ds truck-1)))))))

(deftest the-adapter-hands-the-domain-the-shape-it-expects-test
  (let [ds (fixture/datasource)
        _  (handle ds (command :load-truck {:flavour "vanilla" :quantity 3}))
        e  (first (store/stream ds truck-1))]
    (is (uuid? (:event/id e)))
    (is (= :truck-loaded (:event/type e)))
    (is (= truck-1 (:stream/id e)))
    (is (= 1 (:stream/version e)))
    (is (pos? (:event/position e)))
    (is (= t0 (:event/occurred-at e)))
    (is (inst? (get-in e [:metadata :recorded-at])))
    (is (= {:flavour "vanilla" :quantity 3} (:data e))
        "the data comes back identical, with nothing in between to put it right")
    (is (uuid? (get-in e [:metadata :causation-id])))
    (is (uuid? (get-in e [:metadata :correlation-id]))
        "known envelope UUIDs regain their semantic type at the adapter")))

(deftest application-identifies-facts-before-the-store-test
  (let [ds       (fixture/datasource)
        event-id #uuid "018f7a3e-0000-7000-8000-000000001901"
        cmd      {:command/id     #uuid "0f1c2b3a-0000-4000-8000-000000001901"
                  :command/type   :load-truck
                  :correlation-id #uuid "cc79c083-0000-4000-8000-000000001901"
                  :data           {:flavour "vanilla" :quantity 2}}
        event    (first (application/handle ds truck-1 cmd
                                            (constantly event-id) t0))]
    (is (= event-id (:event/id event)))
    (is (= t0 (:event/occurred-at event)))
    (is (= (:command/id cmd) (get-in event [:metadata :causation-id])))
    (is (= (:correlation-id cmd) (get-in event [:metadata :correlation-id])))
    (is (inst? (get-in event [:metadata :recorded-at])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid event id"
         (application/handle ds truck-1
                             (command :load-truck {:flavour "chocolate"
                                                   :quantity 1})
                             (constantly "not-a-uuid") t0)))))

(deftest the-encoding-is-lossy-and-the-events-do-not-rely-on-it-test
  (let [ds (fixture/datasource)]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 3}))

    (testing "what the database actually holds"
      (is (= {:flavour "vanilla" :quantity 3}
             (-> (jdbc/execute-one! ds ["SELECT data FROM event LIMIT 1"])
                 :event/data .getValue (json/read-str :key-fn keyword)))
          "byte for byte what the domain wrote"))

    (testing "the loss is real, and it is a one-way door"
      ;; JSON has no keyword type, so encoding one is silent and irreversible.
      ;; `:key-fn keyword` restores *keys*, because their names are known in
      ;; advance. There is no such facility for values, and there cannot be:
      ;; by the time you are decoding, a string is all there is.
      (let [round-trip #(json/read-str (json/write-str %) :key-fn keyword)]
        (is (= {:flavour "vanilla"} (round-trip {:flavour :vanilla}))
            "a keyword VALUE goes in and a string comes out")
        (is (= {:flavour "vanilla"} (round-trip {:flavour "vanilla"}))
            "a string goes in and the same string comes out")))

    (testing "which is why nothing in a stream is written as a keyword"
      ;; Not a coercion rule per field, but the property those rules were
      ;; reaching for — and it holds without an adapter doing anything.
      (doseq [e (store/stream ds truck-1)]
        (is (= (:data e)
               (json/read-str (json/write-str (:data e)) :key-fn keyword))
            "every recorded fact survives its own encoding")))))

(deftest the-adapter-rejects-a-keyword-value-before-json-can-erase-its-type-test
  (let [ds    (fixture/datasource)
        cmd   (command :load-truck {})
        event (identified (random-uuid) cmd
                          {:event/type :truck-loaded
                           :data {:flavour :vanilla :quantity 1}})]
    (try
      (store/append ds truck-1 0 [event])
      (is false "lossy values must not reach JSONB")
      (catch clojure.lang.ExceptionInfo e
        (is (= :lossy-json-value (:reason (ex-data e))))))
    (is (empty? (store/stream ds truck-1)))
    (is (zero? (store/current-version ds truck-1)))))

;; ---------------------------------------------------------------------------
;; Optimistic concurrency, enforced by the database
;; ---------------------------------------------------------------------------

(deftest a-stale-expected-version-is-refused-test
  (let [ds  (fixture/datasource)
        cmd (command :load-truck {})]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 5}))
    (try
      (store/append ds truck-1 0
                    [(identified (random-uuid) cmd
                                 {:event/type :truck-loaded
                                  :data {:flavour "vanilla" :quantity 1}})])
      (is false "a stale expected version must not append")
      (catch clojure.lang.ExceptionInfo e
        (is (= :concurrent-modification (:reason (ex-data e))))))))

(deftest a-future-expected-version-is-also-refused-test
  (let [ds  (fixture/datasource)
        cmd (command :load-truck {})]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 5}))
    (try
      (store/append ds truck-1 99
                    [(identified (random-uuid) cmd
                                 {:event/type :truck-loaded
                                  :data {:flavour "vanilla" :quantity 1}})])
      (is false "a future expected version would create a gap")
      (catch clojure.lang.ExceptionInfo e
        (is (= :concurrent-modification (:reason (ex-data e))))))
    (is (= [1] (mapv :stream/version (store/stream ds truck-1))))
    (testing "including a stream that does not exist yet"
      (let [other #uuid "0f1c2b3a-0000-4000-8000-000000000002"]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Concurrent modification"
             (store/append ds other 99
                           [(identified (random-uuid) cmd
                                        {:event/type :truck-loaded
                                         :data {:flavour "vanilla"
                                                :quantity 1}})])))
        (is (zero? (store/current-version ds other)))))))

(deftest concurrency-is-raced-here-not-simulated-test
  (testing "lab 16 counted conflicts deterministically; this one actually races"
    (let [ds (fixture/datasource)
          _  (handle ds (command :load-truck {:flavour "vanilla" :quantity 50}))
          version (store/current-version ds truck-1)
          attempts (mapv (fn [_]
                           (let [cmd (command :buy-flavour {})
                                 event (identified (random-uuid) cmd
                                                   {:event/type :flavour-sold
                                                    :data {:flavour "vanilla"}})]
                             (future
                               (try
                                 (store/append ds truck-1 version [event])
                                 :won
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= :concurrent-modification
                                          (:reason (ex-data e)))
                                     :lost
                                     (throw e)))))))
                         (range 8))
          results (mapv deref attempts)]
      (is (= 1 (count (filter #{:won} results)))
          "exactly one writer may take version n+1")
      (is (= 7 (count (filter #{:lost} results))))
      (testing "and the stream is intact — no gap, no duplicate"
        (is (= [1 2] (map :stream/version (store/stream ds truck-1))))))))

(deftest a-batch-lands-together-or-not-at-all-test
  (let [ds (fixture/datasource)]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 1}))
    (testing "the sale and the depletion share a transaction"
      (handle ds (command :buy-flavour {:flavour "vanilla"}))
      (let [events (store/stream ds truck-1)]
        (is (= [1 2 3] (map :stream/version events)))
        (testing "and one recorded-at, because now() is stable in the transaction"
          (is (= 1 (count (distinct (map #(get-in % [:metadata :recorded-at])
                                         (drop 1 events)))))))))))

(deftest a-failed-later-event-rolls-back-the-whole-batch-test
  (let [ds       (fixture/datasource)
        other    #uuid "0f1c2b3a-0000-4000-8000-000000000002"
        used-id  (random-uuid)
        seed-cmd (command :load-truck {})
        seed     (identified used-id seed-cmd
                             {:event/type :truck-loaded
                              :data {:flavour "vanilla" :quantity 1}})
        cmd      (command :load-truck {})]
    (store/append ds truck-1 0 [seed])
    (try
      (store/append
       ds other 0
       [(identified (random-uuid) cmd
                    {:event/type :truck-loaded
                     :data {:flavour "chocolate" :quantity 1}})
        (identified used-id cmd
                    {:event/type :truck-loaded
                     :data {:flavour "chocolate" :quantity 1}})])
      (is false "the duplicate second id must fail the batch")
      (catch clojure.lang.ExceptionInfo e
        (is (= :duplicate-event-id (:reason (ex-data e))))))
    (is (empty? (store/stream ds other)) "the first insert rolled back too")
    (is (zero? (store/current-version ds other)) "the head update rolled back too")))

;; ---------------------------------------------------------------------------
;; Identity, from lab 4
;; ---------------------------------------------------------------------------

(deftest an-exact-identified-batch-retry-is-idempotent-test
  (let [ds (fixture/datasource)
        id (random-uuid)
        cmd (command :load-truck {})
        event (identified id cmd
                          {:event/type :truck-loaded
                           :data {:flavour "vanilla" :quantity 1}})]
    (store/append ds truck-1 0 [event])
    (is (= id (:event/id (first (store/stream ds truck-1)))))
    (testing "an exact retry returns the recorded fact without inserting twice"
      (is (= [id] (mapv :event/id (store/append ds truck-1 0 [event]))))
      (is (= 1 (count (store/stream ds truck-1)))))
    (testing "reusing the id for a different fact is not a concurrency conflict"
      (try
        (store/append ds truck-1 1
                      [(assoc-in event [:data :quantity] 99)])
        (is false "one event id cannot identify two facts")
        (catch clojure.lang.ExceptionInfo e
          (is (= :duplicate-event-id (:reason (ex-data e)))))))))

(deftest invalid-domain-inputs-and-unknown-semantics-fail-test
  (doseq [quantity [0 -1 1.5 nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quantity must be"
                          (truck/decide
                           (command :load-truck {:flavour "vanilla"
                                                 :quantity quantity})
                           truck/initial-state))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                        (truck/decide (command :teleport {})
                                      truck/initial-state)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}])))
  (is (= {"vanilla" 0}
         (truck/replay [{:event/type :truck-loaded
                         :data {:flavour "vanilla" :quantity 1}}
                        {:event/type :flavour-sold
                         :data {:flavour "vanilla"}}
                        {:event/type :stock-depleted
                         :data {:flavour "vanilla"}}]))))
