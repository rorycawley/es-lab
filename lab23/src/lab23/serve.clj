(ns lab23.serve
  "Start the system with an HTTP server on it, and wait.

  The only thing this namespace does that `demo.clj` does not is stay running."
  (:require [lab23.system :as system])
  (:gen-class))

(def port 3000)

(defn -main [& _]
  (let [sys (system/start (system/serving (system/in-memory) port))]
    (println)
    (println "  Listening on http://localhost:" port)
    (println)
    (println "    curl -s localhost:3000/v1/stock")
    (println "    curl -s localhost:3000/v1/restocks -d '{\"flavour\":\"vanilla\",\"quantity\":2}'")
    (println "    curl -s localhost:3000/v1/sales    -d '{\"flavour\":\"vanilla\"}'")
    (println)
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(system/stop sys)))
    @(promise)))
