(ns lab19.store-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
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
  "Lab 8's four steps, against Postgres. The shape is unchanged."
  [ds cmd]
  (let [history (store/stream ds truck-1)
        version (store/current-version ds truck-1)
        state   (truck/replay history)
        events  (truck/decide cmd state)]
    (store/append ds truck-1 version gen-id t0 cmd events)))

;; ---------------------------------------------------------------------------
;; The domain does not know
;; ---------------------------------------------------------------------------

(deftest lab8s-domain-runs-unchanged-test
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
    (is (= {:flavour "vanilla" :quantity 3} (:data e))
        "the data comes back identical, with nothing in between to put it right")))

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

;; ---------------------------------------------------------------------------
;; Optimistic concurrency, enforced by the database
;; ---------------------------------------------------------------------------

(deftest a-stale-expected-version-is-refused-test
  (let [ds (fixture/datasource)]
    (handle ds (command :load-truck {:flavour "vanilla" :quantity 5}))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Concurrent modification"
         (store/append ds truck-1 0 gen-id t0 (command :load-truck {})
                       [{:event/type :truck-loaded :data {:flavour "vanilla" :quantity 1}}])))))

(deftest concurrency-is-raced-here-not-simulated-test
  (testing "lab 16 counted conflicts deterministically; this one actually races"
    (let [ds (fixture/datasource)
          _  (handle ds (command :load-truck {:flavour "vanilla" :quantity 50}))
          version (store/current-version ds truck-1)
          attempts (mapv (fn [_]
                           (future
                             (try
                               (store/append ds truck-1 version gen-id t0
                                             (command :buy-flavour {})
                                             [{:event/type :flavour-sold
                                               :data {:flavour "vanilla"}}])
                               :won
                               (catch clojure.lang.ExceptionInfo _ :lost))))
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
        (testing "and one recorded-at, because they committed together"
          (is (= 1 (count (distinct (map #(get-in % [:metadata :recorded-at])
                                         (drop 1 events)))))))))))

;; ---------------------------------------------------------------------------
;; Identity, from lab 4
;; ---------------------------------------------------------------------------

(deftest the-event-id-is-minted-by-the-application-test
  (let [ds (fixture/datasource)
        id (random-uuid)]
    (store/append ds truck-1 0 (constantly id) t0 (command :load-truck {})
                  [{:event/type :truck-loaded :data {:flavour "vanilla" :quantity 1}}])
    (is (= id (:event/id (first (store/stream ds truck-1)))))
    (testing "so a retry carrying the same id cannot insert twice"
      (is (thrown? Exception
                   (store/append ds truck-1 1 (constantly id) t0 (command :load-truck {})
                                 [{:event/type :truck-loaded
                                   :data {:flavour "vanilla" :quantity 1}}]))))))
