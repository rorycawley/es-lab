(ns lab32.architecture-test
  "Fitness functions.

  §10 of the build spec asks for one of these by name: *nothing outside
  `accounts/` may require anything from `accounts.*` except its public
  surface, and consider a test that asserts this by scanning `ns` forms.* This
  is that test, and the ones beside it are the same idea applied to the other
  claims this lab makes in prose.

  These deliberately know about source structure, so they are the tests to
  change during a deliberate redesign -- and the ones to read first when
  wondering whether a rule is still real."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab32.accounts.contract :as accounts-contract]
            [lab32.compliance.contract :as compliance-contract]))

(defn- clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (str %) ".clj"))))

(defn- requires [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

(defn- code-only
  "Source with comments and string literals removed.

  A namespace that *explains* why the dispatcher holds no SQL does not contain
  SQL. Scanning raw text would make the prose that teaches a rule fail the
  rule, so this reads what the code does rather than what it says about
  itself. Lab 29 needed the same trick for the same reason."
  [source]
  (-> source
      (str/replace #"(?s)\"(?:\\.|[^\"\\])*\"" "\"\"")
      (str/replace #";[^\n]*" "")))

(defn- without-namespace-names
  "`code-only`, and with this lab's own namespace symbols removed too.

  `lab32.messaging.outbox` is a namespace and `messaging.outbox` is a table,
  and a regex looking for the second finds the first inside it. Requiring a
  namespace is not reaching into a schema, so the names come out before any
  rule about schemas is applied."
  [source]
  (str/replace (code-only source) #"lab32\.[a-z0-9.\-]+" ""))

;; ---------------------------------------------------------------------------
;; §10 — module isolation
;; ---------------------------------------------------------------------------

(deftest a-module-is-reachable-only-through-its-public-surface-test
  (doseq [[module allowed]
          [["accounts"   #{"lab32.accounts.api" "lab32.accounts.contract"}]
           ["compliance" #{"lab32.compliance.api" "lab32.compliance.contract"}]]]
    (doseq [file (clj-files "src/lab32")
            :let [path (str file)]
            ;; The module's own files may require their own internals, and the
            ;; composition root may name both public APIs. Nothing else.
            :when (not (str/includes? path (str "/" module "/")))
            required (requires (slurp file))
            :when (str/starts-with? required (str "lab32." module))]
      (is (contains? allowed required)
          (str path " reaches past " module "'s public surface via " required)))))

(deftest neither-module-knows-the-other-exists-test
  (doseq [[module other] [["accounts" "lab32.compliance"] ["compliance" "lab32.accounts"]]
          file (clj-files (str "src/lab32/" module))
          required (requires (slurp file))]
    (is (not (str/starts-with? required other))
        (str (.getName file) " requires " required))))

(deftest only-the-composition-root-holds-both-modules-test
  (doseq [file (clj-files "src/lab32")
          :let [path (str file)
                required (requires (slurp file))]
          :when (not (or (str/ends-with? path "system.clj")
                         (str/ends-with? path "http/routes.clj")))]
    (is (not (and (contains? required "lab32.accounts.api")
                  (contains? required "lab32.compliance.api")))
        (str path " reaches into both modules' public APIs"))))

(deftest each-module-owns-its-sql-test
  ;; Accounts may name `messaging.outbox`, and only that: putting a message in
  ;; the outbox is what a producer does, and the SQL for it is in the slice
  ;; that opens the transaction. It may not name anything of Compliance's.
  (doseq [file (clj-files "src/lab32/accounts")
          :let [source (without-namespace-names (slurp file))]]
    (is (not (re-find #"\bcompliance\.\w+" source))
        (str (.getName file) " reaches into Compliance's tables"))
    (doseq [[_ table] (re-seq #"\b(messaging\.\w+)" source)]
      (is (= "messaging.outbox" table)
          (str (.getName file) " names " table ", which is not a producer's business"))))
  (doseq [file (clj-files "src/lab32/compliance")]
    (is (not (re-find #"\b(accounts|messaging)\.\w+" (without-namespace-names (slurp file))))
        (str (.getName file) " reaches into another module's tables"))))

;; ---------------------------------------------------------------------------
;; The claim the whole lab rests on
;; ---------------------------------------------------------------------------

(deftest the-fast-path-and-the-slow-path-share-one-function-test
  ;; §5. The reconciler and the listener must be *triggers* for `drain!` and
  ;; must contain no delivery logic of their own. The moment either knows
  ;; something the other does not, the fast path stops being a pure
  ;; optimisation and acceptance test 9 stops meaning anything.
  (let [reconciler (code-only (slurp "src/lab32/messaging/reconciler.clj"))]
    (is (not (re-find #"\b(INSERT|UPDATE|DELETE|SELECT)\b" reconciler))
        "the reconciler contains SQL, so it is doing delivery work of its own")
    (is (not (str/includes? reconciler "outbox"))
        "the reconciler knows what an outbox is")))

(deftest the-shared-transport-holds-the-policy-and-not-the-sql-test
  ;; Lab 29's rule, still: when to give up is the same everywhere, and which
  ;; table to write is not the shared code's business.
  (doseq [path ["src/lab32/messaging/dispatcher.clj" "src/lab32/messaging/worker.clj"]]
    (is (not (re-find #"\b(INSERT|UPDATE|DELETE|SELECT)\b" (code-only (slurp path))))
        (str path " contains SQL")))
  (is (str/includes? (slurp "src/lab32/messaging/worker.clj") "max-attempts")
      "and the worker does carry the give-up policy"))

;; ---------------------------------------------------------------------------
;; Contracts
;; ---------------------------------------------------------------------------

(deftest every-module-declares-a-contract-test
  (doseq [path ["src/lab32/accounts/contract.clj" "src/lab32/compliance/contract.clj"]]
    (let [source (slurp path)]
      (is (str/includes? source "(def contract"))
      (doseq [k [":module" ":schema" ":publishes-events"
                 ":consumes-events" ":provides-queries"]]
        (is (str/includes? source k) (str path " omits " k))))))

(deftest what-is-published-is-not-what-is-recorded-test
  ;; Lab 3's distinction, mechanically. If an internal event type were also
  ;; published, renaming a field inside the aggregate would silently change a
  ;; message another module parses.
  (is (accounts-contract/published-are-not-domain-events?))
  (is (seq accounts-contract/domain-events))
  (is (set/subset? (:consumes-events compliance-contract/contract)
                   (:publishes-events accounts-contract/contract))))

;; ---------------------------------------------------------------------------
;; Where the vocabulary lives
;; ---------------------------------------------------------------------------

(deftest the-event-stream-holds-data-and-the-transport-holds-a-payload-test
  ;; `bb audit` enforces this across the repository's prose. This enforces it
  ;; in the DDL, which is where it would actually do damage: an event's own
  ;; contents are `data`, and `payload` is reserved for a message in transit.
  (let [stream (slurp "resources/migrations/002_accounts_event_stream.sql")]
    (is (re-find #"(?m)^\s+data\s+JSONB" stream))
    (is (not (re-find #"(?m)^\s+payload\s+JSONB" stream))
        "the event stream has acquired a payload column"))
  (doseq [path ["resources/migrations/003_messaging_outbox.sql"
                "resources/migrations/004_compliance_inbox.sql"]]
    (is (re-find #"(?m)^\s+payload\s+JSONB" (slurp path))
        (str path " -- transport carries a payload"))))

(deftest the-pure-core-stays-pure-test
  ;; Lab 0's criterion. `domain.clj` decides what may happen and must never
  ;; acquire a reason to know what time it is or where the data lives.
  ;; Word boundaries, not substrings. The first version of this used
  ;; `str/includes?` and failed on `:unknown-command`, because "unknown"
  ;; contains "now" -- a fitness function that fires on a word inside another
  ;; word teaches people to weaken fitness functions.
  (let [source (code-only (slurp "src/lab32/accounts/domain.clj"))]
    (doseq [forbidden [#"\bjdbc\b" #"\bnow\b" #"\brandom-uuid\b"
                       #"\blog/" #"\bjson\b" #"\bInstant\b"]]
      (is (not (re-find forbidden source))
          (str "domain.clj matches " forbidden)))))

;; ---------------------------------------------------------------------------
;; Lab 23's rule, and lab 21's
;; ---------------------------------------------------------------------------

(deftest the-web-framework-stays-at-the-edge-test
  (doseq [file (clj-files "src/lab32")
          :let [path (str file)]
          :when (not (str/includes? path "/http/"))]
    (is (not (re-find #"reitit|\[ring\.|jetty" (slurp file)))
        (str path " names the web framework"))))

(deftest the-inbound-edge-does-not-touch-a-database-test
  (let [source (code-only (slurp "src/lab32/http/routes.clj"))]
    (is (not (str/includes? source "jdbc")))
    (is (not (re-find #"\b(INSERT|UPDATE|SELECT|DELETE)\b" source)))))

(deftest the-test-container-is-not-an-application-dependency-test
  ;; The rule lab 24 applied to its identity provider: a thing that exists to
  ;; make assertions possible does not get to ship.
  (doseq [file (clj-files "src/lab32")]
    (is (not (str/includes? (slurp file) "testcontainers"))
        (str file " names a test container")))
  (let [declared (->> (str/split-lines (slurp "deps.edn"))
                      (remove #(str/starts-with? (str/trim %) ";"))
                      (str/join "\n"))
        [application _] (str/split declared #":aliases" 2)]
    (is (not (str/includes? application "testcontainers"))
        "testcontainers belongs to the :test and :demo aliases, not to :deps")))

(deftest money-never-passes-through-a-floating-point-value-test
  ;; Gotcha #10 as a source rule rather than a runtime one.
  ;;
  ;; The rule is about *construction and coercion*, not about the word. Naming
  ;; the refusal `:money-from-float`, or asking `(instance? Double x)` in order
  ;; to reject it, is the rule being enforced rather than broken -- so the
  ;; pattern looks for the calls that would produce one.
  (doseq [file (clj-files "src/lab32")]
    (is (not (re-find #"\(double\s|\(float\s|Double/|Float/|\bdoubles\b"
                      (code-only (slurp file))))
        (str file " converts a value to floating point"))))

(deftest the-cursor-trap-is-documented-where-somebody-would-fall-into-it-test
  ;; Gotcha #7 asks for a comment in the code, because the failure it warns
  ;; about is invisible: an "optimisation" to a high-water-mark cursor drops
  ;; events at a rate proportional to concurrency, and no single-threaded test
  ;; will ever show it.
  (let [source (slurp "src/lab32/accounts/repository.clj")]
    (is (str/includes? source "high-water"))
    (is (str/includes? source "seq")))
  (testing "and nothing actually uses one"
    (doseq [file (clj-files "src/lab32")]
      (is (not (re-find #"seq\s*>\s*\?" (slurp file)))
          (str file " tracks a position cursor")))))
