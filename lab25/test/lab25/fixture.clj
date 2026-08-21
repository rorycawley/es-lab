(ns lab25.fixture
  (:require [lab25.postgres :as postgres]
            [lab25.system :as system]))

(defn with-system
  ([f]
   (with-system {} f))
  ([opts f]
   (postgres/truncate!)
   (f (system/start (postgres/config) opts))))
