(ns lab0.contrast-test
  "The same truck, the same two rules, modelled twice.

  Both namespaces work. Both refuse a sale from an empty truck and refuse to
  overload the truck. Neither is badly written. The difference is not quality
  — it is what each one has been tied to, and the tests below are the bill."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [lab0.models.truck :as models]
            [lab0.truck :as truck]))

;; ---------------------------------------------------------------------------
;; What it costs to ask a question
;; ---------------------------------------------------------------------------

(deftest asking-the-domain-model-costs-a-map-literal-test
  (is (true? (truck/sellable? {:stock {"vanilla" 1}} "vanilla"))))

(deftest asking-the-persistence-model-costs-a-store-a-row-and-an-id-test
  (let [store (models/->store)
        id    (models/insert! store {:registration "IC-2019-A"
                                     :stock {"vanilla" 1}})]
    (is (some? (models/sell! store id "vanilla"))))

  (testing "and the question cannot be asked at all without them"
    (is (thrown? Exception (models/sell! (models/->store) 1 "vanilla"))
        "no row, no answer — even though the rule has nothing to do with rows")))

;; ---------------------------------------------------------------------------
;; The rule with no name
;; ---------------------------------------------------------------------------

(deftest the-domain-rules-have-names-test
  (is (some? (resolve 'lab0.truck/sellable?)))
  (is (some? (resolve 'lab0.truck/room-for?))))

(deftest the-persistence-rules-do-not-test
  (testing "there is no models/sellable? and no models/room-for?"
    ;; Not an oversight — there is nowhere to put them. Both rules need a row,
    ;; a row needs a store, and a predicate that takes a store is not a
    ;; statement about ice cream. So they stay inside `sell!` and `load!`,
    ;; three lines from a write, where they cannot be quoted back to the
    ;; person who asked for them.
    (is (nil? (resolve 'lab0.models.truck/sellable?)))
    (is (nil? (resolve 'lab0.models.truck/room-for?)))))

;; ---------------------------------------------------------------------------
;; The same answer twice
;; ---------------------------------------------------------------------------

(deftest the-domain-model-answers-identically-every-time-test
  (let [t {:stock {"vanilla" 2}}]
    (is (= (truck/sell t "vanilla") (truck/sell t "vanilla")))
    (is (= {:stock {"vanilla" 2}} t) "and the truck it was asked about is unchanged")))

(deftest the-persistence-model-cannot-test
  (let [store (models/->store)
        id    (models/insert! store {:registration "IC-2019-A" :stock {"vanilla" 2}})
        once  (models/sell! store id "vanilla")
        twice (models/sell! store id "vanilla")]
    (testing "two identical calls, two different results"
      (is (not= once twice)))
    (testing "because the model has a clock in it"
      ;; `updated_at` is not a fact about ice cream. It is bookkeeping the
      ;; storage mechanism needs, and it has ended up in the thing the
      ;; business believes is its truck.
      (is (some? (:updated_at once)))
      (is (contains? once :created_at))
      (is (contains? once :id) "and a surrogate id nobody in the business uses"))))

;; ---------------------------------------------------------------------------
;; What each model calls a truck
;; ---------------------------------------------------------------------------

(deftest the-domain-model-holds-only-domain-words-test
  (let [t (truck/load-cones truck/empty-truck "vanilla" 3)]
    (is (= #{:stock} (set (keys t)))
        "everything in here is a thing an ice cream seller says")))

(deftest the-persistence-model-holds-storage-words-too-test
  (let [store (models/->store)
        id    (models/insert! store {:registration "IC-2019-A"})
        row   (models/find-by-id store id)
        words (set (keys row))]
    (is (= #{:id :registration :stock :created_at :updated_at} words))
    (testing "three of the five are the mechanism, not the business"
      (is (= #{:id :created_at :updated_at}
             (set/intersection words #{:id :created_at :updated_at}))))
    (testing "and they are named the way a table names things, not a person"
      (is (some #(re-find #"_" (name %)) words)
          "snake_case is SQL's convention leaking upward into the domain"))))
