(ns lab0.architecture-test
  "The isolation, asserted rather than intended.

  Every claim this lab makes about the domain model is a claim about what it
  does *not* touch, and a claim of that shape is worth nothing unless
  something checks it. A README saying \"the core has no dependencies\" is a
  wish. This reads the source and fails the build.

  [Lab21](../lab21) grows this into a full fitness function over ports,
  adapters and a composition root. It starts here, with one rule."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [path] (slurp (io/file "src/lab0" path)))

(defn- code-only
  "The source with comments and string literals removed.

  Needed because this file *discusses* the things it must not use — the
  docstring names HTTP and databases precisely to say there are none. A grep
  that cannot tell an argument from an import will fail on the argument."
  [text]
  (-> (str/join "\n" (remove #(str/starts-with? (str/trim %) ";") (str/split-lines text)))
      (str/replace #"\"(?:[^\"\\\\]|\\\\.)*\"" "")))

(defn- requires [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

(deftest the-model-depends-on-nothing-test
  (testing "not a database, not a framework, not even the rest of this lab"
    (is (empty? (requires (source "truck.clj")))
        "the domain model requires nothing at all")))

(deftest the-model-names-no-technical-concern-test
  (doseq [word ["jdbc" "sql" "http" "ring" "json" "kafka" "queue" "repository"
                "datasource" "connection" "transaction" "orm" "entity" "dao"]]
    (is (not (str/includes? (str/lower-case (code-only (source "truck.clj"))) word))
        (str "truck.clj mentions " word
             " — the model is about ice cream, not about machinery"))))

(deftest the-model-reaches-for-no-effect-test
  (testing "no clock, no randomness, no mutation, no output"
    ;; None of these are forbidden in general. They are forbidden *here*,
    ;; because each one makes an answer depend on something other than the
    ;; question — and a rule you cannot ask twice is not a rule you can trust.
    (doseq [call ["java.util.Date" "System/currentTimeMillis" "random-uuid"
                  "(atom " "swap!" "slurp" "println" "deref"]]
      (is (not (str/includes? (code-only (source "truck.clj")) call))
          (str "truck.clj calls " call)))))

(deftest the-persistence-model-does-all-of-it-test
  (testing "which is the comparison, not an accusation"
    (let [text (source "models/truck.clj")]
      (is (str/includes? text "(atom "))
      (is (str/includes? text "java.util.Date"))
      (is (str/includes? text "swap!"))
      (testing "and so its rules cannot be reached without the machinery"
        (is (str/includes? text "find-by-id"))))))

(deftest the-model-is-small-enough-to-read-test
  (testing "a model nobody can hold in their head is not a shared understanding"
    (let [lines (->> (str/split-lines (source "truck.clj"))
                     (remove str/blank?)
                     (remove #(str/starts-with? (str/trim %) ";"))
                     (drop-while #(not (str/starts-with? % "(def"))))]
      (is (< (count lines) 40)
          (str "truck.clj has grown to " (count lines) " lines of code")))))
