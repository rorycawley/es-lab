(ns lab34.architecture-test
  "Fitness functions. The claim is about which namespace may reach what, and a
  claim of that shape is either asserted mechanically or it decays."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab34.definition :as definition]
            [lab34.onboarding :as onboarding]))

(defn- clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (str %) ".clj"))))

(defn- requires [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

(defn- code-only
  [source]
  (-> source
      (str/replace #"(?s)\"(?:\\.|[^\"\\])*\"" "\"\"")
      (str/replace #";[^\n]*" "")))

;; ---------------------------------------------------------------------------
;; The rule the lab is about
;; ---------------------------------------------------------------------------

(deftest the-fold-cannot-reach-the-registry-test
  ;; If `process.clj` could ask which version is current, an instance's state
  ;; would depend on what was published rather than on what it started under —
  ;; lab 33's forbidden case, with a state machine instead of a scalar.
  (let [source (slurp "src/lab34/process.clj")]
    (is (not (contains? (requires source) "lab34.registry"))
        "process.clj requires the registry")
    (is (not (re-find #"registry/" (code-only source)))
        "process.clj names the registry")))

(deftest the-instance-never-asks-for-the-latest-version-test
  ;; It is handed a resolver — a function from version to definition — and not
  ;; the registry, so there is no argument by which it could. The narrowest
  ;; capability that does the job is the one that cannot be misused.
  (let [source (code-only (slurp "src/lab34/instance.clj"))]
    (is (not (re-find #"latest|current-version" source)))
    (is (not (contains? (requires (slurp "src/lab34/instance.clj")) "lab34.registry")))))

(deftest a-version-is-assigned-once-test
  ;; Pinning means pinning. Only `instance/start` and `migration/move` may
  ;; write `:definition/version`, and a migration is a deliberate act that
  ;; records itself.
  (doseq [file (clj-files "src/lab34")
          :let [path   (str file)
                source (code-only (slurp file))]
          :when (not (or (str/ends-with? path "instance.clj")
                         (str/ends-with? path "migration.clj")))]
    (is (not (re-find #":definition/version\s+\(" source))
        (str path " assigns a definition version"))))

;; ---------------------------------------------------------------------------
;; The definition stays data, and stays checkable
;; ---------------------------------------------------------------------------

(deftest a-definition-holds-no-code-test
  ;; Lab 33's line, applied here. A transition table is a lookup; the moment a
  ;; `:when` or a function shows up in a state, an interpreter has to exist
  ;; and every argument in lab 33 applies again.
  (doseq [[version d] [[1 onboarding/v1] [2 onboarding/v2] [3 onboarding/v3]]]
    (testing (str "v" version)
      (doseq [[state spec] (:states d)]
        (is (every? #{:on :issue :timeout :terminal} (keys spec))
            (str state " declares something other than a transition, a command,"
                 " a timeout or terminality"))
        (is (not-any? fn? (tree-seq coll? seq spec))
            (str state " holds a function"))))))

(deftest every-transition-target-is-a-keyword-test
  ;; No expressions, no vectors, no nesting — the shape a static check can be
  ;; total over.
  (doseq [d [onboarding/v1 onboarding/v2 onboarding/v3]
          state (definition/states d)
          [event target] (definition/transitions d state)]
    (is (keyword? event))
    (is (keyword? target))))

(deftest the-checks-use-no-library-test
  (let [source (slurp "src/lab34/definition.clj")]
    (is (= #{"clojure.set" "clojure.string"} (requires source))))
  (let [declared (->> (str/split-lines (slurp "deps.edn"))
                      (remove #(str/starts-with? (str/trim %) ";;"))
                      (str/join "\n"))
        [application _] (str/split declared #":aliases" 2)]
    (is (= 1 (count (re-seq #"\{:mvn/version" application)))
        "the application has a dependency other than Clojure itself")))

;; ---------------------------------------------------------------------------
;; Counter-example containment
;; ---------------------------------------------------------------------------

(deftest the-counter-example-is-not-reachable-from-anything-real-test
  (doseq [file (clj-files "src/lab34")
          :let [path (str file)]
          :when (not (str/includes? path "engine/"))]
    (is (empty? (filter #(str/starts-with? % "lab34.engine") (requires (slurp file))))
        (str path " reaches for the counter-example"))))

;; ---------------------------------------------------------------------------
;; Purity and determinism
;; ---------------------------------------------------------------------------

(deftest nothing-reads-a-clock-test
  (doseq [file (clj-files "src/lab34")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"Instant/now|System/currentTimeMillis|\(now\)" source))
        (str file " reads a clock"))))

(deftest nothing-mints-an-identifier-test
  (doseq [file (clj-files "src/lab34")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"random-uuid|UUID/randomUUID" source))
        (str file " mints an identifier"))))

(deftest nothing-holds-mutable-state-test
  (doseq [file (clj-files "src/lab34")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"\(atom |\(ref |\(agent |defonce" source))
        (str file " holds mutable state"))))
