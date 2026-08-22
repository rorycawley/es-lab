(ns lab30.platform.validation
  "Closed schemas at the driving edge. Malli stops here."
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn validate!
  [schema value]
  (if-let [explanation (m/explain schema value)]
    (throw (ex-info "Invalid request"
                    {:reason :invalid-request
                     :errors (me/humanize explanation)}))
    value))
