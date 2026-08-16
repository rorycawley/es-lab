(ns es-lab.hello-backend.core
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [es-lab.hello-backend.routes :refer [router]]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn read-config []
  (aero/read-config (io/resource "config.edn")))

(defn -main [& _]
  (let [{:keys [port]} (read-config)]
    (jetty/run-jetty router {:port port :join? true})))
