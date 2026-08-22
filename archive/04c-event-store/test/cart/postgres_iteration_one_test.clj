(ns cart.postgres-iteration-one-test
  (:require [cart.acceptance.iteration-one :as acceptance]
            [cart.adapter.out.persistence.contract :as contract]
            [cart.adapter.out.persistence.postgres :as postgres]
            [cart.slice.add-product-item.adapter.in.http :as add-http]
            [cart.slice.add-product-item.handler :as add-handler]
            [cart.slice.view-cart.adapter.in.http :as view-http]
            [cart.slice.view-cart.handler :as view-handler]
            [cart.test-postgres :as db]
            [clojure.test :refer [deftest use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [platform.http.router :as router])
  (:import [java.time Instant]
           [java.util UUID]))

(def key-ring {:active-key-id "test"
               :keys {"test" "postgres-acceptance-observation-signing-key"}})

(use-fixtures :once db/with-postgres)

(defn- counts []
  (reduce (fn [counts [label table]]
            (assoc counts label
                   (:row-count
                    (jdbc/execute-one!
                     db/*datasource*
                     [(str "SELECT count(*) AS row_count FROM " table)]
                     {:builder-fn rs/as-unqualified-kebab-maps}))))
          {}
          [[:streams "streams"]
           [:events "events"]
           [:commands "command_requests"]]))

(defn- new-context []
  (db/reset-database!)
  (let [store (postgres/new-store db/*datasource*)
        clock (atom (Instant/parse "2026-01-01T00:00:00Z"))
        add   (add-handler/new-handler
               {:event-store store
                :idempotency-store store
                :unit-of-work store
                :key-ring key-ring
                :uuid-fn #(UUID/randomUUID)
                :clock #(deref clock)})
        view  (view-handler/new-handler {:projection-store store
                                         :key-ring key-ring})]
    {:store store
     :clock clock
     :handler (router/handler {:add-product-item (add-http/handler add)
                               :view-cart (view-http/handler view)})
     :counts counts}))

(deftest postgres-satisfies-the-shared-persistence-contract
  (db/reset-database!)
  (contract/assert-contract! (postgres/new-store db/*datasource*)))

(deftest iteration-one-acceptance-against-testcontainers-postgres
  (acceptance/assert-iteration-one! new-context))
