(ns cart.adapter.driven.event-store-contract
  "One set of behaviours both stores must satisfy (SPEC R6.5), so the fast
   in-memory store cannot drift from the real one."
  (:require [cart.port.event-store :as store]
            [clojure.test :refer [is testing]]
            [matcher-combinators.test :refer [thrown-match?]]))

(defn- event [who]
  {:type :cart.event/product-item-added
   :data {:cart-id "c1"
          :product-item {:product-id who :quantity 1 :unit-price 1999}
          :added-at 1735689600000}})

(defn verify
  "Runs the contract against a freshly built store. `new-store` is a 0-arg fn
   returning an EventStore, and `new-id` a 0-arg fn returning a stream id."
  [new-store new-id]
  (testing "empty stream"
    (let [es (new-store)]
      (is (= {:events [] :version 0 :exists? false}
             (store/read-stream es (new-id))))))

  (testing "create then read"
    (let [es (new-store), id (new-id)
          [outcome data] (store/append-to-stream es id [(event "a")] :stream-does-not-exist)]
      (is (= :ok outcome))
      (is (= 1 (:version data)))
      (is (true? (:created-new-stream? data)))
      (is (= 1 (:version (store/read-stream es id))))))

  (testing "append bumps the version by the number of events"
    (let [es (new-store), id (new-id)]
      (store/append-to-stream es id [(event "a")] :stream-does-not-exist)
      (let [[_ data] (store/append-to-stream es id [(event "b") (event "c")] 1)]
        (is (= 3 (:version data)))
        (is (false? (:created-new-stream? data))))))

  (testing "stale expected version conflicts, and writes nothing"
    (let [es (new-store), id (new-id)]
      (store/append-to-stream es id [(event "a")] :stream-does-not-exist)
      (let [[outcome data] (store/append-to-stream es id [(event "b")] 0)]
        (is (= :conflict outcome))
        (is (= 1 (:current data))))
      (is (= 1 (count (:events (store/read-stream es id)))))))

  (testing ":stream-does-not-exist is enforced"
    (let [es (new-store), id (new-id)]
      (store/append-to-stream es id [(event "a")] :stream-does-not-exist)
      (is (= :conflict (first (store/append-to-stream es id [(event "b")]
                                                      :stream-does-not-exist))))))

  (testing ":any skips the check and can never conflict"
    (let [es (new-store), id (new-id)]
      (store/append-to-stream es id [(event "a")] :stream-does-not-exist)
      (is (= :ok (first (store/append-to-stream es id [(event "b")] :any))))
      (is (= :ok (first (store/append-to-stream es id [(event "c")] :any))))))

  (testing ":any creates the stream when it does not exist"
    (let [es (new-store), id (new-id)
          [outcome data] (store/append-to-stream es id [(event "a")] :any)]
      (is (= :ok outcome))
      (is (= 1 (:version data)))))

  (testing "invalid expected versions are rejected, not treated as :any"
    (doseq [expected [nil :bogus -1 1.5]]
      (let [es (new-store), id (new-id)]
        (is (thrown-match? clojure.lang.ExceptionInfo
                           {:expected-version expected}
                           (store/append-to-stream es id [(event "a")] expected)))
        (is (= {:events [] :version 0 :exists? false}
               (store/read-stream es id))))))

  (testing "empty appends are rejected at the port boundary"
    (let [es (new-store), id (new-id)]
      (is (thrown-match? clojure.lang.ExceptionInfo
                         {:stream-id id}
                         (store/append-to-stream es id [] :stream-does-not-exist)))
      (is (= {:events [] :version 0 :exists? false}
             (store/read-stream es id)))))

  (testing "event metadata survives the round trip, and its absence does too"
    (let [es (new-store), id (new-id)
          with-md (assoc (event "a") :metadata {:now 1735689600000
                                                :source "web"})
          without (event "b")]
      (store/append-to-stream es id [with-md without] :stream-does-not-exist)
      (let [[e1 e2] (:events (store/read-stream es id))]
        (is (= with-md e1))
        (is (= without e2))
        (is (not (contains? e2 :metadata))
            "absent must stay absent, not become {}"))))

  (testing "events come back in order"
    (let [es (new-store), id (new-id)]
      (store/append-to-stream es id [(event "a") (event "b") (event "c")]
                              :stream-does-not-exist)
      (is (= ["a" "b" "c"]
             (mapv #(get-in % [:data :product-item :product-id])
                   (:events (store/read-stream es id))))))))
