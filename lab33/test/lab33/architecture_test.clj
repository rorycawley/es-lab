(ns lab33.architecture-test
  "Fitness functions.

  The lab's central claim is a rule about which namespace may require which,
  and a claim of that shape is either asserted mechanically or it decays. The
  most important test in the file is the first one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab33.rules :as rules]))

(defn- clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (str %) ".clj"))))

(defn- requires [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

(defn- code-only
  "Source with comments and string literals removed. A namespace that
  *explains* why it must not read configuration does not read configuration."
  [source]
  (-> source
      (str/replace #"(?s)\"(?:\\.|[^\"\\])*\"" "\"\"")
      (str/replace #";[^\n]*" "")))

;; ---------------------------------------------------------------------------
;; The rule the lab is about
;; ---------------------------------------------------------------------------

(deftest the-decider-cannot-reach-configuration-test
  ;; If `account.clj` ever requires `lab33.rules`, the fold can read a
  ;; parameter — and the moment it does, the same stream folds to a different
  ;; balance depending on a file. Nothing else in this suite would notice,
  ;; because the fold's own code would still look correct.
  (let [source (slurp "src/lab33/account.clj")]
    (is (not (contains? (requires source) "lab33.rules"))
        "account.clj requires the rules namespace")
    (is (not (re-find #"rules/" (code-only source)))
        "account.clj names the rules namespace")))

(deftest only-the-counter-example-reads-configuration-inside-a-fold-test
  ;; Stated the other way round, so that adding a second offender fails too.
  (doseq [file (clj-files "src/lab33")
          :let [path   (str file)
                source (code-only (slurp file))]
          :when (and (re-find #"\(defn evolve" source)
                     (not (str/includes? path "engine/")))]
    (is (not (re-find #"rules/parameter|rules/configure" source))
        (str path " — a fold that reads configuration"))))

(deftest the-counter-examples-are-not-required-by-anything-real-test
  ;; Lab 0's rule for `models/truck.clj`: the tempting version exists to be
  ;; measured, and must never be reachable from the honest one.
  (doseq [file (clj-files "src/lab33")
          :let [path (str file)]
          :when (not (str/includes? path "engine/"))]
    (is (empty? (filter #(str/starts-with? % "lab33.engine") (requires (slurp file))))
        (str path " reaches for a counter-example"))))

;; ---------------------------------------------------------------------------
;; Values, not structure
;; ---------------------------------------------------------------------------

(deftest every-configurable-parameter-is-a-scalar-test
  ;; The closed check only closes anything while every declared predicate
  ;; refuses collections. This is the guard on the guard.
  (doseq [[parameter pred] rules/parameters]
    (testing (str parameter)
      (is (every? #(false? (boolean (pred %))) [[] {} #{} '() [:and [:> :x 1]]])
          "a declared parameter accepts a collection, so the check is not closed"))))

(deftest the-configuration-check-uses-no-library-test
  ;; The argument would be weaker made in prose and contradicted in deps.edn.
  (let [source (slurp "src/lab33/rules.clj")]
    (is (= #{"clojure.string"} (requires source))
        "rules.clj requires something beyond clojure core"))
  (let [declared (->> (str/split-lines (slurp "deps.edn"))
                      (remove #(str/starts-with? (str/trim %) ";;"))
                      (str/join "\n"))
        [application _] (str/split declared #":aliases" 2)]
    (is (= 1 (count (re-seq #"\{:mvn/version" application)))
        "the application has a dependency other than Clojure itself")))

;; ---------------------------------------------------------------------------
;; Purity and determinism
;; ---------------------------------------------------------------------------

(deftest nothing-reads-a-clock-test
  ;; Time is an argument, per lab 11. A function that asks the clock cannot be
  ;; replayed to the same answer twice, which would leave this lab unable to
  ;; tell which difference the configuration caused.
  (doseq [file (clj-files "src/lab33")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"Instant/now|System/currentTimeMillis|\(now\)" source))
        (str file " reads a clock"))))

(deftest nothing-mints-an-identifier-test
  ;; Command ids are derived from the event, never generated — lab 10's rule,
  ;; and the reason `policy_test` can assert an id is stable across a
  ;; configuration change.
  (doseq [file (clj-files "src/lab33")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"random-uuid|UUID/randomUUID" source))
        (str file " mints an identifier"))))

(deftest nothing-holds-mutable-state-test
  (doseq [file (clj-files "src/lab33")
          :let [source (code-only (slurp file))]]
    (is (not (re-find #"\(atom |\(ref |\(agent |defonce" source))
        (str file " holds mutable state"))))
