(ns cart.main
  "Composition root. Reads config, starts the system, waits.

   All configuration lives in resources/config.edn and is read by cart.config."
  (:require [cart.config :as config]
            [cart.system :as system]
            [com.stuartsierra.component :as component]))

(defn -main [& _]
  (let [config (config/read-config)
        system (component/start-system (system/new-system config))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(component/stop-system system)))
    (println (format "Cart HTTP service listening on http://localhost:%d"
                     (get-in config [:http :port])))
    @(promise)))
