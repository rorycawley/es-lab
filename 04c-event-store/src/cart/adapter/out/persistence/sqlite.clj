(ns cart.adapter.out.persistence.sqlite
  "SQLite adapter for the cart persistence ports."
  (:require [cart.adapter.out.persistence.jdbc :as jdbc-store]))

(defn new-store [datasource]
  (jdbc-store/new-store :sqlite datasource))
