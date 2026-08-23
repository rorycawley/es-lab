(ns lab32.http.server
  "Jetty, started and stopped.

  Separate from `routes.clj` because a socket has a lifecycle and a function
  from a map to a map does not. One test proves there is a server; the rest of
  the HTTP suite calls the handler directly."
  (:require [lab32.http.routes :as routes]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server)))

(defn handler
  "The routes as a plain function, with no socket behind them."
  [modules]
  (routes/router modules))

(defn start!
  [modules {:keys [port] :or {port 3000}}]
  (jetty/run-jetty (handler modules) {:port port :join? false}))

(defn stop!
  [^Server server]
  (when server
    (.stop server)))
