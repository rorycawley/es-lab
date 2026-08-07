(ns cart.adapter.driving.system-test
  (:require [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.system :as system]
            [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [ring.adapter.jetty :as jetty])
  (:import [java.io Closeable]))

(definterface Stoppable
  (stop []))

(deftest memory-system-starts-and-stops-http-server
  (let [stopped? (atom false)]
    (with-redefs [jetty/run-jetty (fn [_ _]
                                    (reify Stoppable
                                      (stop [_] (reset! stopped? true))))]
      (let [started (component/start-system
                     (system/new-system {:store :memory
                                         :http  {:port 0}}))]
        (try
          (is (some? (get-in started [:event-store :store])))
          (is (some? (get-in started [:cart-command :handler])))
          (is (some? (get-in started [:cart-query :query])))
          (is (some? (get-in started [:http-server :server])))
          (is (fn? (get-in started [:http-server :handler])))
          (finally
            (component/stop-system started))))
      (is (true? @stopped?)))))

(deftest postgres-database-component-does-not-run-migrations
  (let [closed?    (atom false)
        datasource (reify Closeable
                     (close [_] (reset! closed? true)))]
    (with-redefs [postgres/make-datasource (fn [_] datasource)]
      (let [started (component/start (system/map->Database {:config {}}))]
        (try
          (is (identical? datasource (:datasource started)))
          (finally
            (component/stop started))))
      (is (true? @closed?)))))
