(ns es-lab.task-persistence.core
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [es-lab.task-persistence.db.postgres :as pg]
            [es-lab.task-persistence.routes :as routes]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn- read-config []
  (aero/read-config (io/resource "config.edn")))

(defn -main [& _]
  (let [{:keys [port database-url]} (read-config)
        ds  (jdbc/get-datasource {:jdbcUrl database-url})
        _   (migratus/migrate {:store         :database
                               :migration-dir "migrations"
                               :db            {:datasource ds}})
        ctx {:service-request-port (pg/->PostgresServiceRequestPort ds)
             :audit-port           (pg/->PostgresAuditPort ds)}]
    (jetty/run-jetty (routes/make-router ctx) {:port port :join? true})))
