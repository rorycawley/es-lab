(ns cart.adapter.out.persistence.contract
  "Reusable behavioral contract for every persistence adapter."
  (:require [cart.port.out.event-store :as event-store]
            [cart.port.out.idempotency-store :as idempotency-store]
            [cart.port.out.projection-store :as projection-store]
            [cart.port.out.unit-of-work :as unit-of-work]
            [clojure.test :refer [is testing]])
  (:import [java.time Instant]
           [java.util UUID]))

(def cart-id (UUID/fromString "10000000-0000-0000-0000-000000000001"))
(def product-a (UUID/fromString "20000000-0000-0000-0000-000000000001"))
(def request-a (UUID/fromString "30000000-0000-0000-0000-000000000001"))
(def request-b (UUID/fromString "30000000-0000-0000-0000-000000000002"))
(def request-c (UUID/fromString "30000000-0000-0000-0000-000000000003"))
(def request-d (UUID/fromString "30000000-0000-0000-0000-000000000004"))
(def request-e (UUID/fromString "30000000-0000-0000-0000-000000000005"))
(def event-a (UUID/fromString "40000000-0000-0000-0000-000000000001"))
(def event-b (UUID/fromString "40000000-0000-0000-0000-000000000002"))
(def cart-b (UUID/fromString "10000000-0000-0000-0000-000000000002"))
(def accepted-at (Instant/parse "2026-01-01T00:00:00Z"))

(def canonical-command
  {:command-type "add-product-item"
   :mode "first"
   :product-id (str product-a)
   :quantity 2})

(def result
  {:cart-id (str cart-id)
   :status "open"
   :items [{:product-id (str product-a) :quantity 2}]
   :cart-observation "v1.test.payload.signature"})

(def event
  {:event/id event-a
   :event/type :product-item-added
   :event/version 1
   :event/revision 1
   :event/data {:cart-id cart-id :product-id product-a :quantity 2}
   :event/metadata {}
   :event/accepted-at accepted-at})

(def view
  {:cart-id cart-id :revision 1 :status :open :items {product-a 2}})

(def history
  {:cart-id cart-id
   :revision 1
   :change-type :product-item-added
   :accepted-at accepted-at
   :business-data {:product-id product-a :quantity 2}})

(def canonical-existing
  {:command-type "add-product-item"
   :mode "existing"
   :cart-id (str cart-id)
   :expected-revision 1
   :product-id (str product-a)
   :quantity 1})

(def result-two
  (assoc result
         :items [{:product-id (str product-a) :quantity 3}]
         :cart-observation "v1.test.payload-two.signature"))

(def event-two
  (assoc event
         :event/id event-b
         :event/revision 2
         :event/data (assoc (:event/data event) :quantity 1)))

(def view-two
  (assoc view :revision 2 :items {product-a 3}))

(def history-two
  (assoc history :revision 2 :business-data {:product-id product-a :quantity 1}))

(defn first-acceptance [request-id canonical]
  {:request-id request-id
   :canonical-command canonical
   :stream-key cart-id
   :expected :absent
   :events [event]
   :cart-view view
   :history-entries [history]
   :successful-result result})

(defn second-acceptance [request-id expected]
  {:request-id request-id
   :canonical-command canonical-existing
   :stream-key cart-id
   :expected expected
   :events [event-two]
   :cart-view view-two
   :history-entries [history-two]
   :successful-result result-two})

(defn duplicate-event-acceptance []
  {:request-id request-d
   :canonical-command (assoc canonical-command :product-id (str product-a))
   :stream-key cart-b
   :expected :absent
   :events [(-> event
                (assoc-in [:event/data :cart-id] cart-b))]
   :cart-view (assoc view :cart-id cart-b)
   :history-entries [(assoc history :cart-id cart-b)]
   :successful-result (assoc result :cart-id (str cart-b))})

(defn assert-contract! [store]
  (testing "missing streams and projections have explicit empty results"
    (is (= {:exists? false :revision 0 :events []}
           (event-store/read-stream store cart-id)))
    (is (nil? (projection-store/read-cart-view store cart-id)))
    (is (= [] (projection-store/read-cart-history store cart-id)))
    (is (nil? (idempotency-store/find-command-result store request-a))))

  (testing "one commit atomically writes event, projections and replay result"
    (is (= {:status :ok :result result}
           (unit-of-work/commit! store
                                 (first-acceptance request-a canonical-command))))
    (is (= {:exists? true :revision 1 :events [event]}
           (event-store/read-stream store cart-id)))
    (is (= view (projection-store/read-cart-view store cart-id)))
    (is (= [history] (projection-store/read-cart-history store cart-id)))
    (is (= {:canonical-command canonical-command :result result}
           (idempotency-store/find-command-result store request-a))))

  (testing "equal repeat returns the exact stored result and changes nothing"
    (is (= {:status :idempotent :result result}
           (unit-of-work/commit! store
                                 (first-acceptance request-a canonical-command))))
    (is (= 1 (:revision (event-store/read-stream store cart-id))))
    (is (= 1 (count (projection-store/read-cart-history store cart-id)))))

  (testing "non-equal request reuse wins before stream expectation"
    (is (= {:status :request-misuse}
           (unit-of-work/commit!
            store
            (first-acceptance request-a (assoc canonical-command :quantity 3)))))
    (is (= 1 (:revision (event-store/read-stream store cart-id)))))

  (testing "a different request cannot create an established stream"
    (let [conflict (unit-of-work/commit! store
                                         (first-acceptance request-b
                                                           canonical-command))]
      (is (= :conflict (:status conflict)))
      (is (= 1 (:current-revision conflict))))
    (is (nil? (idempotency-store/find-command-result store request-b)))
    (is (= 1 (count (projection-store/read-cart-history store cart-id)))))

  (testing "a request ID used by a conflict remains available"
    (is (= {:status :ok :result result-two}
           (unit-of-work/commit! store (second-acceptance request-b 1))))
    (is (= 2 (:revision (event-store/read-stream store cart-id))))
    (is (= view-two (projection-store/read-cart-view store cart-id)))
    (is (= [history history-two]
           (projection-store/read-cart-history store cart-id))))

  (testing "a stale expected revision writes no command or projections"
    (let [conflict (unit-of-work/commit! store (second-acceptance request-c 1))]
      (is (= :conflict (:status conflict)))
      (is (= 2 (:current-revision conflict))))
    (is (nil? (idempotency-store/find-command-result store request-c)))
    (is (= 2 (:revision (projection-store/read-cart-view store cart-id)))))

  (testing "storage constraint failure rolls back the entire acceptance"
    (is (thrown? Exception
                 (unit-of-work/commit! store (duplicate-event-acceptance))))
    (is (= {:exists? false :revision 0 :events []}
           (event-store/read-stream store cart-b)))
    (is (nil? (projection-store/read-cart-view store cart-b)))
    (is (nil? (idempotency-store/find-command-result store request-d))))

  (testing "misaligned projections are rejected before persistence"
    (is (thrown? Exception
                 (unit-of-work/commit!
                  store
                  (assoc (second-acceptance request-e 2)
                         :cart-view (assoc view-two :revision 99)))))
    (is (= 2 (:revision (event-store/read-stream store cart-id))))
    (is (nil? (idempotency-store/find-command-result store request-e)))))
