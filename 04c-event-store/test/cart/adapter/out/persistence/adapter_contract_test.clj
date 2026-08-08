(ns cart.adapter.out.persistence.adapter-contract-test
  (:require [cart.adapter.out.persistence.contract :as contract]
            [cart.adapter.out.persistence.memory :as memory]
            [cart.adapter.out.persistence.sqlite :as sqlite]
            [clojure.test :refer [deftest testing]]
            [platform.persistence.datasource :as datasource]
            [platform.persistence.migrations :as migrations])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-sqlite-store [f]
  (let [directory (Files/createTempDirectory
                   "cart-adapter-contract-"
                   (make-array FileAttribute 0))
        jdbc-url  (str "jdbc:sqlite:" (.resolve directory "cart.sqlite3"))]
    (migrations/migrate-sqlite! jdbc-url)
    (let [datasource (datasource/sqlite-datasource {:jdbc-url jdbc-url})]
      (try
        (f (sqlite/new-store datasource))
        (finally
          (datasource/close! datasource))))))

(deftest memory-and-sqlite-satisfy-the-same-persistence-contract
  (testing "memory"
    (contract/assert-contract! (memory/new-store)))
  (testing "SQLite"
    (with-sqlite-store contract/assert-contract!)))
