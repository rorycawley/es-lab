(ns lab24.architecture-test
  "A fitness function for the shape of the code.

  A diagram in a README is a wish. These are assertions: they read the source
  and fail the build if the dependency arrows ever turn round. Every lab in
  this repository asserts its ideas in tests; this asserts its *structure*.
  It is intentionally orthogonal to behaviour, adapter and E2E tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [path] (slurp (io/file "src/lab24" path)))

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

(deftest http-is-an-adapter-and-nothing-below-it-knows-test
  (testing "ring and reitit appear in exactly one namespace"
    (let [files (->> (file-seq (io/file "src/lab24"))
                     (filter #(str/ends-with? (str %) ".clj")))]
      (doseq [f files
              :let [name (.getName f)
                    uses-web? (some #(str/includes? % "reitit")
                                    (requires (slurp f)))]]
        (is (or (not uses-web?) (= "http.clj" name))
            (str name " requires reitit — HTTP is an adapter, not a dependency"))))
    (testing "and the application layer is one of the things that does not know"
      (doseq [ns-name (requires (source "app.clj"))]
        (is (not (str/includes? ns-name "reitit")))
        (is (not (str/includes? ns-name "ring")))))
    (testing "nor does the driving adapter it calls"
      (doseq [ns-name (requires (source "adapter/intake.clj"))]
        (is (not (str/includes? ns-name "reitit")))))))

(deftest every-driving-level-is-callable-on-its-own-test
  (testing "which is what lets HTTP, the demo and three test namespaces all drive"
    ;; A driving adapter enters wherever it needs to. That is only possible
    ;; because no level requires the one above it: `intake` does not need HTTP,
    ;; and `app` does not need `intake`.
    (is (not-any? #(str/includes? % "http") (requires (source "adapter/intake.clj")))
        "intake needs no transport")
    (is (not-any? #(str/includes? % "intake") (requires (source "app.clj")))
        "the application needs no validation edge")
    (is (not-any? #(str/includes? % "app") (requires (source "core/truck.clj")))
        "and the core needs no application")))

;; ---------------------------------------------------------------------------
;; Authentication is an adapter concern, and stops there
;; ---------------------------------------------------------------------------

(def ^:private token-libraries ["buddy" "jwt" "jose"])

(deftest only-two-namespaces-know-what-a-token-is-test
  (testing "one verifies them and one models the keys; nothing else has heard of JOSE"
    (let [files  (->> (file-seq (io/file "src/lab24"))
                      (filter #(str/ends-with? (str %) ".clj")))
          namers (->> files
                      (filter (fn [f] (some (fn [required]
                                              (some #(str/includes? required %) token-libraries))
                                            (requires (slurp f)))))
                      (mapv #(.getName %))
                      sort)]
      (is (= ["auth.clj" "oidc.clj"] namers)
          "a token library reaching further than the edge is authentication leaking inward"))))

(defn- code-only
  "The source with comments and string literals removed.

  Needed because these files *discuss* the things they must not use — lab 23
  hit the same trap, where `app.clj`'s docstring named the adapters precisely
  to explain that it uses none. A grep that cannot tell an argument from an
  import will fail on the argument."
  [text]
  (-> (str/join "\n" (remove #(str/starts-with? (str/trim %) ";") (str/split-lines text)))
      (str/replace #"\"(?:[^\"\\]|\\.)*\"" "")
      str/lower-case))

(deftest the-core-has-never-heard-of-a-credential-test
  (testing "not the word in a comment — the identifier in the code"
    (doseq [f core-files
            forbidden ["token" "bearer" "jwt" "claim" "role" "principal" "scope"]]
      (is (not (str/includes? (code-only (source f)) forbidden))
          (str f " uses " forbidden
               " — the core decides on values, not on credentials"))))
  (testing "while naming a refusal is fair game"
    ;; `:not-authorised` is domain vocabulary. The core is allowed to say why
    ;; it said no; it is not allowed to know what a bearer token is.
    (is (str/includes? (source "core/truck.clj") ":not-authorised"))))

(deftest the-core-never-requires-the-permission-table-test
  (testing "RBAC is a boundary artefact, for the same reason a schema is (lab 22)"
    (doseq [f core-files]
      (is (not (contains? (requires (source f)) "lab24.authority"))
          (str f " requires the RBAC table")))
    (testing "and the edge does"
      (is (contains? (requires (source "adapter/intake.clj")) "lab24.authority")))))

(deftest the-identity-provider-is-not-a-dependency-of-the-application-test
  (testing "an IdP is a dependency of your tests; `dev` is where the mock lives"
    (doseq [f (->> (file-seq (io/file "src/lab24"))
                   (filter #(str/ends-with? (str %) ".clj")))]
      (is (not (str/includes? (slurp f) "mock-oauth2-server"))
          (str (.getName f) " names the mock provider")))
    (is (not (str/includes? (slurp (io/file "src/lab24/system.clj")) "mock-idp"))
        "not even the composition root: the provider is configuration, not a component"))
  (testing "and the main dependency list does not carry it either"
    (let [deps             (code-only (slurp (io/file "deps.edn")))
          [before-aliases] (str/split deps #":aliases")]
      (is (not (str/includes? before-aliases "mock-oauth2-server"))
          "an IdP on the application's classpath is an IdP you could accidentally use"))))

(deftest the-application-layer-never-authenticates-test
  (testing "it is handed a command whose actor is already established"
    (doseq [required (requires (source "app.clj"))]
      (is (not (str/includes? required "auth")) "app.clj requires the authenticator")
      (is (not (str/includes? required "authority")) "app.clj requires the role table"))))

(deftest jetty-is-named-only-by-the-composition-root-test
  (testing "a web server has a lifecycle, so Component owns it — in one file"
    (let [namers (->> (file-seq (io/file "src/lab24"))
                      (filter #(str/ends-with? (str %) ".clj"))
                      (filter #(str/includes? (slurp %) "ring.adapter.jetty"))
                      (mapv #(.getName %)))]
      (is (= ["system.clj"] namers)))))

(deftest schemas-live-in-the-shell-test
  (testing "and are used by the driving edge and the store adapter"
    (is (seq (filter #(str/includes? % "malli") (requires (source "schema/command.clj")))))
    (is (contains? (requires (source "adapter/intake.clj")) "lab24.schema.command"))
    (is (contains? (requires (source "adapter/postgres.clj")) "lab24.schema.event"))))

(deftest validation-happens-before-the-application-layer-test
  (testing "lab 2: at the adapter, before the command object is constructed"
    (is (empty? (filter #(str/includes? % "schema") (requires (source "app.clj"))))
        "app.clj validates — that belongs at the driving edge")))

(deftest the-application-layer-depends-on-ports-not-adapters-test
  (let [app (source "app.clj")]
    (is (contains? (requires app) "lab24.port.driven"))
    (testing "and on no adapter at all"
      ;; Checked against what it *requires*, not what it says — the docstring
      ;; mentions adapters precisely to explain that it does not use one.
      (is (empty? (filter #(str/includes? % "adapter") (requires app)))
          "app.clj requires an adapter — the shell must be told, not choose"))))

(deftest the-audited-command-outcome-is-one-driven-operation-test
  (testing "facts, actor-bearing ledger entry and messages cannot split"
    (let [application (source "app.clj")
          composition (source "system.clj")]
      (is (str/includes? application "driven/commit-command"))
      (is (not (str/includes? application "driven/append")))
      (is (not (str/includes? application "driven/enqueue")))
      (is (not (str/includes? composition "memory/outbox")))
      (is (not (str/includes? composition "postgres/outbox"))))))

(deftest only-the-composition-root-constructs-adapters-test
  (testing "search the source for a concrete adapter and find one file"
    (let [files (->> (file-seq (io/file "src/lab24"))
                     (filter #(str/ends-with? (str %) ".clj"))
                     (remove #(str/includes? (str %) "/adapter/")))
          namers (filter #(str/includes? (slurp %) "lab24.adapter.postgres") files)]
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
    (is (str/includes? (slurp (io/file "dev/lab24/demo.clj")) "println"))
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
      (is (< (count lines) 45)
          (str "app.clj has grown to " (count lines) " lines of code")))))
