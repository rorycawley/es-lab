(ns lab30.registry.api
  "Registry's public module API. The command and query slices remain private
  implementation details behind this surface."
  (:require [lab30.platform.validation :as validation]
            [lab30.registry.register-entity :as register-entity]
            [lab30.registry.search-entities :as search-entities]))

(defrecord Registry [register search rebuild-search])

(defn new-module [datasource]
  (let [context {:datasource datasource}]
    (->Registry
     (fn [request]
       (let [request (register-entity/prepare request)]
         (register-entity/handle!
          context (validation/validate! register-entity/Request request))))
     (fn [request]
       (let [request (search-entities/prepare request)]
         (search-entities/handle
          context (validation/validate! search-entities/Request request))))
     #(register-entity/rebuild-search! context))))

(defn register! [registry request] ((:register registry) request))
(defn search [registry request] ((:search registry) request))
(defn rebuild-search! [registry] ((:rebuild-search registry)))
