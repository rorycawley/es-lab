(ns es-lab.task-persistence.uuid
  (:import [com.github.f4b6a3.uuid UuidCreator]))

(defn uuid7 ^java.util.UUID []
  (UuidCreator/getTimeOrderedEpoch))
