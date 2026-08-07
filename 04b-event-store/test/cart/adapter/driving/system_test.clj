(ns cart.adapter.driving.system-test
  (:require [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.migrate :as migrate]
            [cart.system :as system]
            [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component])
  (:import [java.io Closeable]))

(deftest memory-system-starts-and-stops-http-server
  (let [started (component/start-system
                 (system/new-system {:store :memory
                                     :http  {:port 0}}))]
    (try
      (is (some? (get-in started [:event-store :store])))
      (is (some? (get-in started [:http-server :server])))
      (is (fn? (get-in started [:http-server :handler])))
      (finally
        (component/stop-system started)))))

(deftest postgres-database-component-does-not-run-migrations
  (let [migrated?  (atom false)
        closed?    (atom false)
        datasource (reify Closeable
                     (close [_] (reset! closed? true)))]
    (with-redefs [postgres/make-datasource (fn [_] datasource)
                  postgres/migrate! (fn [_] (reset! migrated? true))]
      (let [started (component/start (system/map->Database {:config {}}))]
        (try
          (is (identical? datasource (:datasource started)))
          (is (false? @migrated?))
          (finally
            (component/stop started))))
      (is (true? @closed?)))))

(deftest explicit-migrator-closes-its-datasource
  (let [migrated?  (atom false)
        closed?    (atom false)
        datasource (reify Closeable
                     (close [_] (reset! closed? true)))]
    (migrate/migrate! (fn [_] datasource)
                      (fn [ds]
                        (is (identical? datasource ds))
                        (reset! migrated? true))
                      {:jdbc-url "jdbc:postgresql://example/event_store"})
    (is (true? @migrated?))
    (is (true? @closed?))))
