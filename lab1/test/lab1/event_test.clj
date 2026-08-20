(ns lab1.event-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [lab1.event :as event]))

(deftest flavour-sold-shape-test
  (testing "each example has the flavour-sold event type"
    (doseq [example event/examples]
      (is (= :flavour-sold (:event/type example))))))

(deftest flavour-sold-flavour-test
  (testing "each example names a flavour"
    (doseq [example event/examples]
      (is (keyword? (:flavour example))))))

(deftest flavour-sold-vanilla-test
  (is (= {:event/type :flavour-sold
          :flavour    :vanilla}
         event/flavour-sold-vanilla)))

(deftest flavour-sold-vanilla-envelope-test
  (testing "the envelope carries type, with the flavour in the data"
    (is (= {:event/type :flavour-sold
            :data       {:flavour :vanilla}}
           event/flavour-sold-vanilla-envelope))
    (is (keyword? (get-in event/flavour-sold-vanilla-envelope [:data :flavour])))))

(deftest examples-are-distinct-test
  (is (= (count event/examples)
         (count (distinct event/examples)))))

(deftest a-recorded-event-says-what-when-and-who-test
  (let [e event/flavour-sold-vanilla-recorded]
    (testing "a description of what happened"
      (is (= :flavour-sold (:event/type e)))
      (is (= :vanilla (get-in e [:data :flavour]))))
    (testing "when it happened in the domain"
      (is (inst? (:event/occurred-at e))))
    (testing "the identity of the entity involved"
      (is (uuid? (get-in e [:data :truck-id]))))
    (testing "and, separately, the circumstances of writing it down"
      (is (inst? (get-in e [:metadata :recorded-at])))
      (is (= {:type :user :id "till-2"} (get-in e [:metadata :actor]))))))

(deftest occurring-and-recording-are-different-moments-test
  (testing "the till reported the sale a minute after the cone was handed over"
    (let [e event/flavour-sold-vanilla-recorded]
      (is (pos? (compare (get-in e [:metadata :recorded-at])
                         (:event/occurred-at e)))))))

(deftest an-event-is-immutable-test
  (testing "correcting an event produces a new value; the fact is unchanged"
    (let [original event/flavour-sold-vanilla
          amended  (assoc original :flavour :strawberry)]
      (is (= {:event/type :flavour-sold :flavour :vanilla} original))
      (is (not= original amended)))))

(deftest new-information-does-not-replace-old-test
  (testing "a correction accretes; the original fact is still in the history"
    (let [history [event/flavour-sold-vanilla event/sale-reversed]]
      (is (= 2 (count history)))
      (is (some #{event/flavour-sold-vanilla} history)
          "the mistaken sale is still there, not overwritten")
      (is (= :sale-reversed (:event/type (last history)))))))

(deftest intent-beats-a-state-delta-test
  (testing "both are true after the same sale; only one names what the business did"
    (is (= :flavour-sold (:event/type event/intent)))
    (is (= :stock-level-changed (:event/type event/state-delta)))
    (testing "the delta says where the number landed, not why"
      (is (contains? (:data event/state-delta) :to))
      (is (not (contains? (:data event/intent) :to))))))

(deftest granularity-is-irreversible-test
  (testing "identical resulting price, different facts"
    (is (= (get-in event/price-corrected [:data :price])
           (get-in event/price-increased [:data :price])))
    (is (not= (:event/type event/price-corrected)
              (:event/type event/price-increased))))
  (testing "the coarse name collapses both, and nothing recovers which it was"
    (let [coarsen #(assoc % :event/type :price-changed)]
      (is (= (coarsen event/price-corrected)
             (coarsen event/price-increased)))
      (is (= event/price-changed (coarsen event/price-corrected)))
      (is (= event/price-changed (coarsen event/price-increased))))))

(deftest a-mistake-is-undone-by-a-reversal-test
  (testing "not by deleting the sale — the trail that it happened survives"
    (is (= :sale-reversed (:event/type event/sale-reversed)))
    (is (keyword? (get-in event/sale-reversed [:data :reason-code])))))

(deftest an-actor-is-a-kind-as-well-as-an-id-test
  (testing "a process manager is not a person"
    (is (= :user (get-in event/restocked-by-a-person [:metadata :actor :type])))
    (is (= :system (get-in event/restocked-by-a-process [:metadata :actor :type])))
    (testing "same fact, different actor kinds — recording one as the other is false"
      (is (= (:data event/restocked-by-a-person)
             (:data event/restocked-by-a-process)))
      (is (not= (get-in event/restocked-by-a-person [:metadata :actor])
                (get-in event/restocked-by-a-process [:metadata :actor]))))))

(deftest an-actor-id-is-opaque-test
  (testing "never a token or credential: append-only storage cannot revoke one"
    (doseq [e [event/restocked-by-a-person
               event/restocked-by-a-process
               event/flavour-sold-vanilla-recorded]]
      (let [actor (get-in e [:metadata :actor])]
        (is (= #{:type :id} (set (keys actor))))
        (is (not-any? #{:token :jwt :credential :password :bearer}
                      (keys actor)))))))

(defn- all-keys
  "Every key of every map nested anywhere inside `m`."
  [m]
  (into #{} (mapcat keys) (filter map? (tree-seq coll? seq m))))

(deftest nothing-about-the-machine-is-in-the-event-test
  (testing "pod names, handler classes and SQL timings belong in logs, at any depth"
    (doseq [e [event/flavour-sold-vanilla-recorded
               event/restocked-by-a-person
               event/restocked-by-a-process]]
      (is (empty? (set/intersection
                   (all-keys e)
                   #{:pod :hostname :handler-class :sql-duration-ms :stack-trace}))))))

(deftest all-keys-really-does-recurse-test
  (testing "so the assertion above means what it says"
    (is (contains? (all-keys event/flavour-sold-vanilla-recorded) :type)
        ":type lives two maps deep, inside :metadata's :actor")))

(deftest the-store-already-contains-an-identity-test
  (testing "(stream-id, version) names exactly one event, permanently"
    (let [natural-key (juxt :stream/id :stream/version)
          e           event/flavour-sold-in-a-stream]
      (is (= [(:stream/id e) 17] (natural-key e)))
      (testing "and it is not the minted id — two identities, two jobs"
        (is (uuid? (:event/id e)))
        (is (not= (:event/id e) (:stream/id e)))))))

(deftest a-version-is-assigned-not-observed-test
  (testing "which is why it cannot collide the way a data-derived key can"
    (let [e       event/flavour-sold-in-a-stream
          next-ev (update e :stream/version inc)]
      (is (not= ((juxt :stream/id :stream/version) e)
                ((juxt :stream/id :stream/version) next-ev)))
      (testing "even though the two events are otherwise identical facts"
        (is (= (:data e) (:data next-ev)))
        (is (= (:event/type e) (:event/type next-ev)))))))

(def ^:private data-derived-key
  (juxt :event/type :event/occurred-at #(get-in % [:data :truck-id])))

(deftest identity-can-be-derived-from-the-properties-test
  (testing "a duplicate arrival is recognisable without a minted id"
    (let [e         event/flavour-sold-vanilla-recorded
          duplicate (assoc e :metadata {:recorded-at #inst "2026-08-16T14:35:00.000-00:00"
                                        :actor       {:type :user :id "till-2"}})]
      (is (not= e duplicate) "arrived twice, written down at different moments")
      (is (= (data-derived-key e) (data-derived-key duplicate))
          "and the derived key sees one event, which is the point"))))

(deftest but-a-data-derived-key-collides-test
  (testing "two different sales, same truck, same millisecond"
    (let [vanilla   event/flavour-sold-vanilla-recorded
          chocolate (assoc-in vanilla [:data :flavour] :chocolate)]
      (is (not= vanilla chocolate) "genuinely different facts")
      (is (= (data-derived-key vanilla) (data-derived-key chocolate))
          "yet the derived key cannot tell them apart")))
  (testing "and widening the key does not rescue it"
    (is (= 1 (count (distinct [event/flavour-sold-vanilla
                               event/flavour-sold-vanilla])))
        "two identical sales are the same value; no function of them can differ")))
