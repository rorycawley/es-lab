(ns lab31.performance.workload
  "A deterministic registry workload with a tuning set and a disjoint proof set.")

(defn registration-number [n]
  (format "REG-%08d" n))

(defn entity [n]
  {:entity-id           n
   :registration-number (registration-number n)
   :name                (str "Example Entity " n)})

(defn corpus [size]
  (mapv entity (range size)))

(defn- keys-at [indexes]
  (mapv registration-number indexes))

(defn tuning-keys [size]
  (keys-at [0
            (quot size 5)
            (quot size 3)
            (quot size 2)
            (dec size)]))

(defn proof-entity-ids [size]
  [17
   101
   997
   (quot size 7)
   (quot size 4)
   (quot (* 2 size) 5)
   (quot (* 3 size) 5)
   (quot (* 4 size) 5)
   (- size 1003)
   (- size 101)
   (- size 2)])

(defn proof-keys [size]
  (conj (keys-at (proof-entity-ids size))
        "REG-NOT-PRESENT"))

(defn proof-expected-ids [size]
  (conj (proof-entity-ids size) nil))
