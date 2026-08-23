(ns lab34.migration
  "Moving a running instance from one definition to another.

  This namespace exists because of an answer nobody likes: when the process
  changes, instances already inside it have to go somewhere, and there is no
  general rule for where. `:awaiting-sanctions` became `:awaiting-screening`
  and also absorbed part of `:awaiting-manual` — no algorithm derives that.
  Somebody who understands the business has to say.

  So a migration is an explicit map, written down, checked, and applied as a
  deliberate act:

      {:awaiting-sanctions :awaiting-screening
       :awaiting-manual    :awaiting-screening}

  Two properties make it safe. It is **total** over the states that actually
  have instances in them — a migration that forgets one is refused rather than
  discovered. And it **records itself on the instance**, so an instance that
  has been moved can say so, which matters when its recorded history contains
  a transition to a state its current definition has never heard of."
  (:require [clojure.string :as str]
            [lab34.definition :as definition]
            [lab34.instance :as instance]))

(defn problems
  "Why this migration cannot be applied to these instances."
  [mapping from-definition to-definition instances]
  (let [occupied (into #{} (map instance/status) instances)
        targets  (definition/states to-definition)
        sources  (definition/states from-definition)]
    (into []
          (concat
           (for [[source _] (sort-by (comp str key) mapping)
                 :when (not (contains? sources source))]
             (str source " is not a state in v" (definition/version-of from-definition)))

           (for [[source target] (sort-by (comp str key) mapping)
                 :when (not (contains? targets target))]
             (str source " maps to " target ", which v"
                  (definition/version-of to-definition) " does not declare"))

           ;; The one that matters. A state somebody is sitting in, with no
           ;; instruction for where they go, is not an omission to default —
           ;; it is a question nobody answered.
           (for [status (sort-by str occupied)
                 :when (and (not (contains? targets status))
                            (not (contains? mapping status)))]
             (str "instances are in " status " and the migration does not say where they go"))))))

(defn check!
  [mapping from-definition to-definition instances]
  (let [found (problems mapping from-definition to-definition instances)]
    (when (seq found)
      (throw (ex-info (str "Cannot migrate: " (str/join "; " found))
                      {:reason :cannot-migrate :problems found})))
    mapping))

(defn- move
  [mapping to-version instance]
  (let [status (instance/status instance)
        target (get mapping status status)]
    (-> instance
        (assoc :definition/version to-version)
        (assoc-in [:state :status] target)
        (update-in [:state :migrations] (fnil conj [])
                   {:from-version (:definition/version instance)
                    :to-version   to-version
                    :from-status  status
                    :to-status    target}))))

(defn migrate
  "Move every instance onto `to-definition`, or refuse and say why.

  Returns the instances, moved. Note what it does *not* touch: the recorded
  `:history` of transitions each instance has already made. Those happened,
  under a definition that said they could, and rewriting them to look like the
  new process would be falsifying the record for tidiness. The migration is
  appended as its own fact instead."
  [mapping from-definition to-definition instances]
  (check! mapping from-definition to-definition instances)
  (mapv (partial move mapping (definition/version-of to-definition)) instances))

(defn migrated?
  [instance]
  (boolean (seq (get-in instance [:state :migrations]))))
