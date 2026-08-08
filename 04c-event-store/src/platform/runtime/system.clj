(ns platform.runtime.system
  "Composition root for the Iteration 0 walking skeleton."
  (:require [com.stuartsierra.component :as component]
            [platform.http.router :as http]
            [ring.adapter.jetty :as jetty]))

(defrecord HttpServer [config server handler]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [handler (http/handler {:ready? (constantly true)})
            server  (jetty/run-jetty handler
                                     {:host  (:host config)
                                      :port  (:port config)
                                      :join? false})]
        (assoc this :handler handler :server server))))
  (stop [this]
    (when server
      (.stop server))
    (assoc this :handler nil :server nil)))

(defn new-system [{:keys [http]}]
  (component/system-map
   :http-server (map->HttpServer {:config http})))
