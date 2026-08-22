(ns lab31.performance.search
  "Two correct implementations of the exact registration-number lookup job.")

(defn scan-one [entities registration]
  (loop [remaining entities]
    (when-let [entity (first remaining)]
      (if (= registration (:registration-number entity))
        entity
        (recur (next remaining))))))

(defn scan-many [entities registrations]
  (mapv #(scan-one entities %) registrations))

(defn build-index [entities]
  (into {} (map (juxt :registration-number identity)) entities))

(defn indexed-many [index registrations]
  (mapv index registrations))
