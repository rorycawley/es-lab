(ns cart.migrate
  "Explicit migration entrypoint. Deployment should run this as a one-shot step
   before starting the HTTP service."
  (:require [cart.adapter.driven.event-store-postgres :as postgres]
            [cart.main :as main]))

(defn migrate!
  ([db-config]
   (migrate! postgres/make-datasource postgres/migrate! db-config))
  ([make-datasource migrate-fn db-config]
   (let [datasource (make-datasource db-config)]
     (try
       (migrate-fn datasource)
       (finally
         (.close datasource))))))

(defn -main [& _]
  (migrate! (main/db-config-from-env))
  (println "Database migrations applied"))
