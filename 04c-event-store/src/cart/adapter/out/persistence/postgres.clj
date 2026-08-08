(ns cart.adapter.out.persistence.postgres
  "PostgreSQL adapter for the cart persistence ports."
  (:require [cart.adapter.out.persistence.jdbc :as jdbc-store]))

(defn new-store [datasource]
  (jdbc-store/new-store :postgres datasource))
