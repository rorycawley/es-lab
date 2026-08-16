(ns cart.acceptance.sqlite-iteration-one-test
  (:require [cart.acceptance.iteration-one :as acceptance]
            [cart.adapter.out.persistence.sqlite :as sqlite]
            [cart.slice.add-product-item.adapter.in.http :as add-http]
            [cart.slice.add-product-item.handler :as add-handler]
            [cart.slice.view-cart.adapter.in.http :as view-http]
            [cart.slice.view-cart.handler :as view-handler]
            [clojure.test :refer [deftest]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [platform.http.router :as router]
            [platform.persistence.datasource :as datasource]
            [platform.persistence.migrations :as migrations])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.util UUID]))

(def key-ring {:active-key-id "test"
               :keys {"test" "sqlite-acceptance-observation-signing-key"}})

(def datasources (atom []))

(defn- counts [datasource]
  (into {}
        (for [[label table] [[:streams "streams"]
                             [:events "events"]
                             [:commands "command_requests"]]]
          [label
           (:row-count
            (jdbc/execute-one!
             datasource
             [(str "SELECT count(*) AS row_count FROM " table)]
             {:builder-fn rs/as-unqualified-kebab-maps}))])))

(defn- new-context []
  (let [directory (Files/createTempDirectory
                   "cart-sqlite-acceptance-"
                   (make-array FileAttribute 0))
        jdbc-url  (str "jdbc:sqlite:" (.resolve directory "cart.sqlite3"))
        _         (migrations/migrate-sqlite! jdbc-url)
        datasource (datasource/sqlite-datasource {:jdbc-url jdbc-url
                                                  :pool-size 8})
        store      (sqlite/new-store datasource)
        clock      (atom (Instant/parse "2026-01-01T00:00:00Z"))
        add         (add-handler/new-handler
                     {:event-store store
                      :idempotency-store store
                      :unit-of-work store
                      :key-ring key-ring
                      :uuid-fn #(UUID/randomUUID)
                      :clock #(deref clock)})
        view        (view-handler/new-handler {:projection-store store
                                               :key-ring key-ring})]
    (swap! datasources conj datasource)
    {:store store
     :clock clock
     :handler (router/handler {:add-product-item (add-http/handler add)
                               :view-cart (view-http/handler view)})
     :counts #(counts datasource)}))

(deftest iteration-one-acceptance-against-sqlite
  (try
    (acceptance/assert-iteration-one! new-context)
    (finally
      (doseq [datasource @datasources]
        (datasource/close! datasource))
      (reset! datasources []))))
