(ns lab22.architecture-test
  "A fitness function for the shape of the code.

  A diagram in a README is a wish. These are assertions: they read the source
  and fail the build if the dependency arrows ever turn round. Every lab in
  this repository asserts its ideas in tests; this asserts its *structure*."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [path] (slurp (io/file "src/lab22" path)))

(def core-files ["core/truck.clj" "core/policy.clj" "core/contract.clj"])

(defn- requires
  "The namespaces a file requires."
  [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

;; ---------------------------------------------------------------------------
;; The arrows point inward
;; ---------------------------------------------------------------------------

(deftest the-core-depends-on-nothing-of-ours-test
  (testing "a core namespace may require clojure.*, and nothing else"
    (doseq [f core-files]
      (doseq [required (requires (source f))]
        (is (str/starts-with? required "clojure.")
            (str f " requires " required
                 " — the core must not know about ports, adapters or Component"))))))

(deftest the-core-never-names-component-test
  (testing "Component is a composition tool; it belongs at the edge"
    (doseq [f core-files]
      (is (empty? (filter #(str/includes? % "component") (requires (source f))))
          (str f " requires Component")))))

(deftest the-core-never-names-malli-test
  (testing "the archive's rule, enforced rather than commented"
    ;; archive/04b-event-store/deps.edn carries the comment this asserts:
    ;;   ;; Schemas (used by the shell and tests only, never by cart.core)
    ;;
    ;; Malli is pure, so purity is not the reason. A schema describes data
    ;; crossing a boundary, and the core has no boundaries — it has values,
    ;; handed to it by an edge that already checked them.
    (doseq [f core-files]
      (is (empty? (filter #(str/includes? % "malli") (requires (source f))))
          (str f " requires Malli — a schema is a boundary artefact")))))

(deftest schemas-live-in-the-shell-test
  (testing "and are used by the driving edge and the store adapter"
    (is (seq (filter #(str/includes? % "malli") (requires (source "schema/command.clj")))))
    (is (contains? (requires (source "adapter/intake.clj")) "lab22.schema.command"))
    (is (contains? (requires (source "adapter/postgres.clj")) "lab22.schema.event"))))

(deftest validation-happens-before-the-application-layer-test
  (testing "lab 2: at the adapter, before the command object is constructed"
    (is (empty? (filter #(str/includes? % "schema") (requires (source "app.clj"))))
        "app.clj validates — that belongs at the driving edge")))

(deftest the-application-layer-depends-on-ports-not-adapters-test
  (let [app (source "app.clj")]
    (is (contains? (requires app) "lab22.port"))
    (testing "and on no adapter at all"
      ;; Checked against what it *requires*, not what it says — the docstring
      ;; mentions adapters precisely to explain that it does not use one.
      (is (empty? (filter #(str/includes? % "adapter") (requires app)))
          "app.clj requires an adapter — the shell must be told, not choose"))))

(deftest only-the-composition-root-constructs-adapters-test
  (testing "search the source for a concrete adapter and find one file"
    (let [files (->> (file-seq (io/file "src/lab22"))
                     (filter #(str/ends-with? (str %) ".clj"))
                     (remove #(str/includes? (str %) "/adapter/")))
          namers (filter #(str/includes? (slurp %) "lab22.adapter.postgres") files)]
      (is (= ["system.clj"] (mapv #(.getName %) namers))
          "swapping an adapter should be a one-line change, not an audit"))))

;; ---------------------------------------------------------------------------
;; The core is pure, checked by reading it
;; ---------------------------------------------------------------------------

(def effects
  "Ways a function stops being a function of its inputs."
  {"random-uuid"               "randomness"
   "java.util.UUID/randomUUID" "randomness"
   "System/currentTimeMillis"  "the clock"
   "(java.util.Date."          "the clock"
   "(atom "                    "mutable state"
   "swap!"                     "mutable state"
   "slurp"                     "the filesystem"
   "println"                   "output"})

(deftest the-core-reaches-for-nothing-test
  (doseq [f core-files
          [call what] effects]
    (is (not (str/includes? (source f) call))
        (str f " calls " call " — that is " what ", and it belongs in an adapter"))))

(deftest the-shell-is-where-the-effects-live-test
  (testing "the same calls, in the places designed to hold them"
    (is (str/includes? (source "adapter/clock.clj") "randomUUID"))
    (is (str/includes? (source "adapter/memory.clj") "atom"))
    (is (str/includes? (source "demo.clj") "println"))
    (testing "which is the point: they are contained, not absent"
      (is (pos? (count effects))))))

;; ---------------------------------------------------------------------------
;; The shell stays thin
;; ---------------------------------------------------------------------------

(deftest the-application-layer-holds-no-business-logic-test
  (testing "no branching except on emptiness — a rule here is a rule in two places"
    (let [app  (source "app.clj")
          body (str/join "\n" (remove #(str/starts-with? (str/trim %) ";")
                                      (str/split-lines app)))]
      (doseq [form ["(if " "(cond" "(case " "(condp "]]
        (is (not (str/includes? body form))
            (str "app.clj contains " form " — business logic has leaked out of the core"))))))

(deftest the-application-layer-is-small-test
  (testing "thinness is the measure, so measure it"
    (let [lines (->> (str/split-lines (source "app.clj"))
                     (remove str/blank?)
                     (remove #(str/starts-with? (str/trim %) ";"))
                     (drop-while #(not (str/starts-with? % "(defn"))))]
      (is (< (count lines) 40)
          (str "app.clj has grown to " (count lines) " lines of code")))))
