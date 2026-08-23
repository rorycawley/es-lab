(ns lab34.registry
  "Versioned definitions, and the check a configuration file cannot perform.

  A definition is checkable on its own (`definition.clj`). Publishing a *new*
  one is a different question, because by then there are instances running,
  and the registry is the only thing that knows both.

      publishing v2 that adds a step          fine, nobody is in it
      publishing v2 that removes a step       refused, if anybody is sitting there

  That second line is the point. No config file, no feature flag and no
  environment variable can refuse an edit on the grounds of what is currently
  in flight, because none of them knows. Making the definition data is what
  makes the check possible; making it *versioned* is what makes it necessary."
  (:require [clojure.string :as str]
            [lab34.definition :as definition]
            [lab34.instance :as instance]
            [lab34.migration :as migration]))

(defn registry
  "An empty registry. A map from version to definition, plus the name it is
  for, so a caller cannot publish an onboarding process into a payments
  registry by passing the wrong argument."
  [process-name]
  {:process/name process-name :versions {}})

(defn definition-at
  [registry version]
  (or (get-in registry [:versions version])
      (throw (ex-info "No such definition version"
                      {:reason :unknown-version :version version}))))

(defn resolver
  "A function from version to definition, for `instance/observe`.

  Handed to instances rather than the registry itself, so nothing downstream
  can ask what the current version is. The narrowest capability that does the
  job is the one that cannot be misused."
  [registry]
  (partial definition-at registry))

(defn latest
  [registry]
  (when-let [version (last (sort (keys (:versions registry))))]
    (definition-at registry version)))

(defn versions [registry] (vec (sort (keys (:versions registry)))))

(defn- stranded
  "Instances whose current state does not exist in `definition`.

  The migration question, asked as a set difference."
  [registry definition instances]
  (let [available (definition/states definition)]
    (into []
          (for [i instances
                :when (and (instance/in-flight? i (resolver registry))
                           (not (contains? available (instance/status i))))]
            {:process/id (:process/id i)
             :status     (instance/status i)
             :running    (:definition/version i)}))))

(defn- installable
  "Everything wrong with a definition that does not depend on what is running."
  [registry definition handled-commands]
  (let [version (definition/version-of definition)]
    (into []
          (concat
           (definition/problems definition handled-commands)

           (when (not= (:process/name registry) (:process/name definition))
             [(str "this registry is for " (:process/name registry)
                   ", not " (:process/name definition))])

           (when (nil? version) ["a definition must carry a :process/version"])

           (when (contains? (:versions registry) version)
             [(str "version " version " is already published, and a published"
                   " definition is immutable")])))))

(defn problems
  "Why this version cannot be published.

  Three reasons, and the third is the one that needs a registry:

    the definition is not a process        `definition/problems`
    the version is not new                 a published definition is immutable
    live instances would be stranded       only answerable here"
  [registry definition instances handled-commands]
  (into (installable registry definition handled-commands)
        (for [{:keys [process/id status running]} (stranded registry definition instances)]
          (str "instance " id " is in " status " under v" running
               ", which v" (definition/version-of definition) " does not declare"))))

(defn publish
  "Install a new version, or refuse and say why.

  Returns the updated registry. Refusing is the feature: an incomplete process
  should be impossible to install and a breaking one impossible to install
  *while somebody is inside it*, rather than something an instance discovers
  by getting stuck."
  ([registry definition] (publish registry definition [] nil))
  ([registry definition instances handled-commands]
   (let [found (problems registry definition instances handled-commands)]
     (when (seq found)
       (throw (ex-info (str "Cannot publish: " (str/join "; " found))
                       {:reason :cannot-publish :problems found})))
     (assoc-in registry [:versions (definition/version-of definition)] definition))))

(defn release
  "Publish a breaking version and move the instances it would strand, as one
  act. Returns `{:registry … :instances …}`.

  This function exists because `publish` and `migrate` cannot be sequenced.
  Publishing first is refused while anybody is stranded; migrating first pins
  those instances to a version the registry does not have yet, so they can no
  longer be resolved at all. Each order is blocked by the other, and the
  deadlock is not an accident of this implementation — it is the design saying
  something true.

  **A breaking change and its migration are one act.** You cannot ship a
  process that deletes a step without saying where the people standing in it
  go, and 'say where they go' is not a follow-up task that might slip to next
  sprint. Making it one function is what stops it being two, one of which gets
  forgotten.

  Note that it returns the instances. An operation that moves people has to
  hand them back, or the caller keeps working with the ones from before.

  `from-definition` is the version the *mapping was written against*, not a
  claim that every instance is pinned to it — instances on older versions are
  fine as long as the state each is actually standing in has somewhere to go,
  which `migration/check!` verifies against the instances themselves rather
  than against the version number. A real system with many live versions would
  want a mapping per source version; one is enough to make the point."
  [registry from-definition to-definition mapping instances handled-commands]
  (let [found (installable registry to-definition handled-commands)]
    (when (seq found)
      (throw (ex-info (str "Cannot release: " (str/join "; " found))
                      {:reason :cannot-publish :problems found})))
    ;; Three populations, and only one of them moves. A finished application
    ;; is not carried onto the new process: it is over, its history says what
    ;; it went through, and dragging it forward would imply it is still
    ;; subject to a process it already left.
    (let [live (filterv #(instance/in-flight? % (resolver registry)) instances)
          done (filterv #(not (instance/in-flight? % (resolver registry))) instances)]
      (migration/check! mapping from-definition to-definition live)
      {:registry  (assoc-in registry [:versions (definition/version-of to-definition)]
                            to-definition)
       :instances (into (migration/migrate mapping from-definition to-definition live)
                        done)})))

(defn in-flight
  "Every instance still running, grouped by the version it is pinned to.

  The operational question this design exists to be able to answer: *what is
  running, and under what?* A deployment of hardcoded logic cannot answer it
  at all — see `engine/hardcoded.clj`."
  [registry instances]
  (->> instances
       (filter #(instance/in-flight? % (resolver registry)))
       (group-by :definition/version)
       (into (sorted-map))))
