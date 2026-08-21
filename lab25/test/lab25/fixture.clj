(ns lab25.fixture
  (:require [lab25.postgres :as postgres]
            [lab25.system :as system]))

(defn with-system [f]
  (postgres/truncate!)
  (f (system/start (postgres/config))))
