(ns lab20.store-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [lab20.fixture :as fixture]
            [lab20.store :as store]))

(use-fixtures :each fixture/with-store)

(def stream-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def command-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")
(def correlation-1 #uuid "0f1c2b3a-0000-4000-8000-000000000201")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(defn- event [id quantity]
  {:event/id id
   :event/type :truck-loaded
   :event/occurred-at t0
   :data {:flavour "vanilla" :quantity quantity}
   :metadata {:causation-id command-1 :correlation-id correlation-1}})

(deftest exact-identified-append-retry-is-idempotent-test
  (let [ds (fixture/datasource)
        proposed (event #uuid "0f1c2b3a-0000-4000-8000-000000000301" 2)
        first-result (store/append ds stream-1 0 [proposed])
        retry-result (store/append ds stream-1 0 [proposed])]
    (is (= first-result retry-result))
    (is (= 1 (count (store/stream ds stream-1))))
    (is (= command-1 (get-in (first retry-result) [:metadata :causation-id])))))

(deftest future-expected-version-cannot-create-a-stream-gap-test
  (let [ds (fixture/datasource)
        failure (try
                  (store/append ds stream-1 5
                                [(event #uuid "0f1c2b3a-0000-4000-8000-000000000302" 2)])
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= :concurrent-modification (:reason (ex-data failure))))
    (is (zero? (store/current-version ds stream-1)))
    (is (empty? (store/stream ds stream-1)))))
