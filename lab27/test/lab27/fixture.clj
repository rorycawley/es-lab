(ns lab27.fixture
  (:require [lab27.postgres :as postgres]
            [lab27.recorder :as recorder]
            [lab27.system :as system]))

(defn with-system
  ([f]
   (with-system {} f))
  ([opts f]
   (recorder/start!)
   (postgres/truncate!)
   ;; Telemetry is reset per scenario for the same reason the tables are: a
   ;; test that asserts "one trace" should not be reading the last one.
   (recorder/clear!)
   (f (system/start (postgres/config) opts))))
