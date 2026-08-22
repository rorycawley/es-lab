(ns lab30.fixture
  (:require [lab30.postgres :as postgres]
            [lab30.registry.api :as registry]))

(defn with-registry [f]
  (postgres/truncate!)
  (f (registry/new-module (postgres/datasource))))

(defn register!
  ([module name] (register! module name {}))
  ([module name overrides]
   (let [id (or (:entity-id overrides) (random-uuid))]
     (registry/register!
      module
      (merge {:entity-id id
              :registration-number (str "REG-" id)
              :euid nil
              :name name
              :legal-form :llc
              :filing-language :fr
              :status :active
              :registered-on "2020-01-01"}
             overrides)))))

(defn names [response]
  (mapv :name (:found response)))
