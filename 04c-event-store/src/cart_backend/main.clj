(ns cart-backend.main
  "Starts the backend and blocks until shutdown."
  (:require [com.stuartsierra.component :as component]
            [platform.runtime.config :as config]
            [platform.runtime.system :as system]))

(defn -main [& _]
  (let [config  (config/read-config)
        running (component/start-system (system/new-system config))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(component/stop-system running)))
    (println (format "Cart backend listening on http://%s:%d"
                     (get-in config [:http :host])
                     (get-in config [:http :port])))
    @(promise)))
