(ns es-lab.task-persistence.service-requests.queries
  (:require [es-lab.task-persistence.service-requests.port :as sr]))

(defn list-service-requests-handler [{:keys [service-request-port]}]
  (fn [_]
    {:status 200 :body {:requests (sr/list-all service-request-port)}}))
