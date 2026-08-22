(ns lab28.architecture-test
  "Fitness functions for vertical slices and module contracts.

  These tests intentionally know source structure. They are orthogonal to the
  behaviour/integration/E2E split and may change during a deliberate redesign."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (str %) ".clj"))))

(defn- requires [text]
  (->> (re-seq #"\[([a-z0-9.\-]+)\s+:as" text)
       (map second)
       set))

(deftest code-is-grouped-by-business-capability-and-use-case-test
  (doseq [path ["src/lab28/catalog/change_price.clj"
                "src/lab28/catalog/get_product.clj"
                "src/lab28/ordering/place_order.clj"
                "src/lab28/ordering/get_order.clj"
                "src/lab28/ordering/catalog_price_changed.clj"]]
    (is (.isFile (io/file path)) (str path " — the use case should be visible")))
  (testing "technical stereotypes are not the top-level structure"
    (is (empty? (filter #(re-find #"/(controllers?|services?|repositories?|entities?)/"
                                  (str %))
                        (clj-files "src/lab28"))))))

(deftest modules-communicate-only-through-public-contracts-test
  (testing "Catalog knows nothing about Ordering"
    (doseq [file (clj-files "src/lab28/catalog")
            required (requires (slurp file))]
      (is (not (str/starts-with? required "lab28.ordering"))
          (str (.getName file) " requires " required))))
  (testing "Ordering may know Catalog's contract and nothing behind it"
    (doseq [file (clj-files "src/lab28/ordering")
            required (filter #(str/starts-with? % "lab28.catalog")
                             (requires (slurp file)))]
      (is (= "lab28.catalog.contract" required)
          (str (.getName file) " bypasses Catalog's public contract via " required)))))

(deftest each-module-owns-its-sql-test
  (doseq [file (clj-files "src/lab28/catalog")]
    (is (not (str/includes? (slurp file) "ordering."))
        (str (.getName file) " reaches into Ordering's schema")))
  (doseq [file (clj-files "src/lab28/ordering")]
    (is (not (re-find #"catalog\.(product|outbox)" (slurp file)))
        (str (.getName file) " reaches into Catalog's tables"))))

(deftest commands-and-queries-do-not-share-a-generic-model-test
  (is (not (.exists (io/file "src/lab28/model.clj"))))
  (doseq [slice ["catalog/change_price.clj" "catalog/get_product.clj"
                 "ordering/place_order.clj" "ordering/get_order.clj"]]
    (let [source (slurp (io/file "src/lab28" slice))]
      (is (str/includes? source "(def Request") (str slice " owns no request shape"))
      (is (re-find #"\(defn handle!?" source) (str slice " owns no handler")))))

(deftest cross-cutting-concerns-wrap-slices-at-the-api-test
  (doseq [api ["src/lab28/catalog/api.clj" "src/lab28/ordering/api.clj"]
          :let [source (slurp (io/file api))]]
    (is (str/includes? source "behaviour/validation"))
    (is (str/includes? source "behaviour/observation"))))

(deftest module-public-surfaces-do-not-expose-database-handles-test
  (doseq [api ["src/lab28/catalog/api.clj" "src/lab28/ordering/api.clj"]
          :let [source (slurp (io/file api))]]
    (is (not (re-find #"\(defrecord\s+\w+\s+\[[^\]]*datasource" source))
        (str api " exposes its database handle through the public module value"))))

(deftest the-database-declares-separate-owners-test
  (let [schema (slurp (io/file "resources/schema.sql"))]
    (is (str/includes? schema "SCHEMA catalog AUTHORIZATION catalog_module"))
    (is (str/includes? schema "SCHEMA ordering AUTHORIZATION ordering_module"))
    (is (str/includes? schema "REVOKE CREATE ON SCHEMA public FROM PUBLIC"))))

;; ---------------------------------------------------------------------------
;; Telemetry containment
;;
;; Lab 23 confined reitit, ring and jetty to a driving adapter and the
;; composition root, and failed the build if they spread. An observability
;; library needs the rule more than a web framework does, because the reason to
;; adopt one is that it is useful everywhere — which is exactly how a codebase
;; ends up with four hundred call sites it cannot migrate.
;; ---------------------------------------------------------------------------

(def ^:private telemetry-libraries #"steffan-westcott|io\.opentelemetry|org\.slf4j|logback")

(deftest only-two-namespaces-name-the-telemetry-library-test
  (doseq [file (clj-files "src/lab28")
          :let [path (str file)]
          :when (not (or (str/ends-with? path "platform/telemetry.clj")
                         (str/ends-with? path "system.clj")))]
    (is (not (re-find telemetry-libraries (slurp file)))
        (str path " names a telemetry library directly"))))

(deftest the-two-that-do-take-one-half-each-test
  (testing "producing telemetry"
    (let [source (slurp (io/file "src/lab28/platform/telemetry.clj"))]
      (is (str/includes? source "clj-otel.api"))
      (is (not (str/includes? source "clj-otel.sdk"))
          "how telemetry is produced is not where it is sent")))
  (testing "configuring where it goes"
    (let [source (slurp (io/file "src/lab28/system.clj"))]
      (is (str/includes? source "clj-otel.sdk"))
      (is (not (re-find #"clj-otel\.api" source))))))

(deftest the-collectors-tests-read-are-not-an-application-dependency-test
  ;; The same rule lab 24 applied to its identity provider: a thing that exists
  ;; to make assertions possible does not get to ship.
  (doseq [file (clj-files "src/lab28")]
    (is (not (str/includes? (slurp file) "sdk.testing"))
        (str file " names an in-memory test collector")))
  (let [declared (->> (str/split-lines (slurp (io/file "deps.edn")))
                      (remove #(str/starts-with? (str/trim %) ";"))
                      (str/join "\n"))
        [application _] (str/split declared #":aliases" 2)]
    (is (not (str/includes? application "sdk-testing"))
        "sdk-testing belongs to the :test and :demo aliases, not to :deps")))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(deftest the-index-lives-with-the-data-it-indexes-test
  (doseq [path ["src/lab28/catalog/search_products.clj"
                "src/lab28/ordering/search_orders.clj"]]
    (is (.isFile (io/file path))
        (str path " -- each module searches what it owns"))))

(deftest nothing-can-rank-one-owner-against-the-other-test
  ;; The cost this lab accepts. Two indexes in two schemas cannot produce one
  ;; ranked list, and the only place allowed to hold both answers at once is
  ;; the composition root -- so no module can quietly grow a cross-owner query.
  (doseq [file (clj-files "src/lab28")
          :let [required (requires (slurp file))]
          :when (not (str/ends-with? (str file) "system.clj"))]
    (is (not (and (contains? required "lab28.catalog.api")
                  (contains? required "lab28.ordering.api")))
        (str file " reaches into both modules' public APIs"))))

(deftest the-customer-is-not-in-any-search-index-test
  (let [schema (slurp (io/file "resources/schema.sql"))
        generated (re-seq #"(?s)GENERATED ALWAYS AS \((.*?)\) STORED" schema)]
    (is (= 2 (count generated)) "two derived vectors, one per module")
    (doseq [[_ expression] generated]
      (is (not (str/includes? expression "customer_email"))
          "a trigram index over an address is a people search"))))

(deftest the-plan-harness-is-not-an-application-dependency-test
  (doseq [file (clj-files "src/lab28")]
    (is (not (str/includes? (slurp file) "EXPLAIN"))
        (str file " -- measuring the planner is a test concern"))))

(defn- defn-body
  "One top-level `defn` form's source, by name."
  [path fn-name]
  (let [source (slurp (io/file path))
        start  (str/index-of source (str "(defn " fn-name))]
    (when start
      (let [rest-of (subs source (+ start 6))
            next-defn (str/index-of rest-of "\n(def")]
        (if next-defn (subs rest-of 0 next-defn) rest-of)))))

;; ---------------------------------------------------------------------------
;; Vendors
;;
;; The claim this lab makes is that Stripe and SendGrid are replaceable. These
;; are the tests that make it a claim rather than an aspiration.
;; ---------------------------------------------------------------------------

(defn- code-only
  "Source with comments and string literals removed.

  A namespace that *explains* why SendGrid has no idempotency key does not
  depend on SendGrid. Scanning raw text would make the prose that teaches the
  rule fail the rule, so this reads what the code does rather than what it says
  about itself."
  [source]
  (-> source
      (str/replace #"(?s)\"(?:\\.|[^\"\\])*\"" "\"\"")
      (str/replace #";[^\n]*" "")))

(def ^:private vendor-words
  #"(?i)stripe|sendgrid|payment_intent|\bpi_|pm_card|whsec|sg\.|x-message-id")

(deftest a-vendor-is-named-only-by-its-own-adapter-test
  (doseq [file (clj-files "src/lab28")
          :let [path (str file)]
          :when (not (or (str/includes? path "/adapter/")
                         (str/ends-with? path "system.clj")
                         (str/ends-with? path "http.clj")))
          :let [hit (re-find vendor-words (code-only (slurp file)))]]
    (is (nil? hit) (str path " names a provider: " hit))))

(deftest each-adapter-names-only-its-own-vendor-test
  (is (nil? (re-find #"(?i)sendgrid"
                     (code-only (slurp (io/file "src/lab28/payments/adapter/stripe.clj"))))))
  (is (nil? (re-find #"(?i)stripe"
                     (code-only (slurp (io/file "src/lab28/notifications/adapter/sendgrid.clj")))))))

(deftest the-modules-depend-on-ports-not-adapters-test
  ;; Dependency inversion, asserted. `charge_order.clj` requires the port and
  ;; has no idea an adapter exists.
  (doseq [[slice required] [["src/lab28/payments/charge_order.clj" "lab28.payments.port"]
                            ["src/lab28/notifications/send_receipt.clj"
                             "lab28.notifications.port"]]]
    (let [requires-of (requires (slurp (io/file slice)))]
      (is (contains? requires-of required))
      (is (empty? (filter #(str/includes? % ".adapter.") requires-of))
          (str slice " reaches for a concrete adapter")))))

(deftest only-the-composition-root-constructs-a-provider-test
  ;; Naming an adapter and constructing one are different acts, and only the
  ;; second is a lock-in. `http.clj` names Stripe's adapter because a webhook
  ;; route *is* provider-specific -- the signature scheme and the event
  ;; vocabulary are Stripe's, and a second provider would get its own route
  ;; beside it. What it must not do is decide who takes the money.
  (doseq [file (clj-files "src/lab28")
          :let [path (str file)]
          :when (not (str/ends-with? path "system.clj"))]
    (is (nil? (re-find #"(stripe|sendgrid|memory-gateway|memory-emailer)/(gateway|emailer)"
                       (slurp file)))
        (str path " constructs a provider"))))

(deftest every-port-has-more-than-one-implementation-test
  ;; A port with one implementation is a guess. Two is evidence, and the
  ;; contract suite is what holds them to the same promises.
  (doseq [[port implementations]
          [["payments" ["src/lab28/payments/adapter/stripe.clj"
                        "src/lab28/payments/adapter/memory.clj"]]
           ["notifications" ["src/lab28/notifications/adapter/sendgrid.clj"
                             "src/lab28/notifications/adapter/memory.clj"]]]]
    (doseq [path implementations]
      (is (.isFile (io/file path)) (str port ": " path)))))

(deftest only-one-namespace-knows-how-a-call-is-protected-test
  ;; A retry helper sprinkled at four hundred call sites is not a policy, it is
  ;; a habit -- and the thing you most want to change after an outage teaches
  ;; you something is the policy.
  (doseq [file (clj-files "src/lab28")
          :let [path (str file)]
          :when (not (str/ends-with? path "platform/resilience.clj"))]
    (is (not (re-find #"diehard|dev\.failsafe|CircuitBreakerOpen"
                      (code-only (slurp file))))
        (str path " names a resilience library directly"))))

(deftest each-adapter-declares-what-it-may-retry-test
  ;; Not a style rule. An adapter that does not state its retryable failures
  ;; either retries nothing or retries everything, and one of those two
  ;; double-charges people.
  (doseq [path ["src/lab28/payments/adapter/stripe.clj"
                "src/lab28/notifications/adapter/sendgrid.clj"]]
    (is (str/includes? (slurp (io/file path)) "(def retry-reasons")
        (str path " has no retry policy of its own"))))

(deftest the-relay-policy-is-shared-and-the-sql-is-not-test
  ;; Lab 25's rule and this lab's, together: when to give up is the same
  ;; everywhere, and which table to write is the module's business.
  (let [relay (slurp (io/file "src/lab28/platform/relay.clj"))]
    ;; Case-sensitive on purpose: `update` the Clojure function is not
    ;; `UPDATE` the statement.
    (is (not (re-find #"\b(INSERT|UPDATE|DELETE|SELECT)\b" relay))
        "the shared relay contains no SQL")
    (is (str/includes? relay "attempts-before-death")
        "and does contain the policy"))
  (doseq [module ["catalog" "ordering" "payments"]]
    (let [outbox (slurp (io/file (str "src/lab28/" module "/outbox.clj")))]
      (is (str/includes? outbox (str module ".dead_letter"))
          (str module " does not own a dead letter table")))))

(deftest the-web-framework-stays-where-lab-23-put-it-test
  (doseq [file (clj-files "src/lab28")
          :let [path (str file)]
          :when (not (or (str/ends-with? path "http.clj")
                         (str/ends-with? path "system.clj")))]
    (is (not (re-find #"reitit|\[ring\.|jetty" (slurp file)))
        (str path " names the web framework"))))

(deftest the-fake-providers-are-not-an-application-dependency-test
  (doseq [file (clj-files "src/lab28")]
    (is (not (re-find #"fake-stripe|fake-sendgrid|lab28\.chaos" (slurp file)))
        (str file " names a test double"))))

(deftest webhooks-are-verified-before-they-are-believed-test
  ;; The ordering matters more than the presence: parsing before verifying
  ;; means acting on bytes nobody vouched for.
  (let [source (slurp (io/file "src/lab28/http.clj"))
        verify (str/index-of source "verify-signature")
        parse  (str/index-of source "translate-event")]
    (is (some? verify))
    (is (some? parse))
    (is (< verify parse) "the signature check must come first")))

(deftest the-inbound-edge-does-not-write-to-a-database-test
  ;; A driving adapter translates and delegates. If it can write, it will.
  (let [source (slurp (io/file "src/lab28/http.clj"))]
    (is (not (str/includes? source "jdbc")))
    (is (not (re-find #"(?i)INSERT|UPDATE|SELECT" source)))))

(deftest the-pure-rule-stays-pure-test
  ;; Lab 0's criterion, still holding at lab 28 -- but the assertion had to
  ;; move, and saying so is the point.
  ;;
  ;; Labs 26 and 27 asserted that the whole of `place_order.clj` never named
  ;; telemetry. That stopped being the right test when lab 28 gave Ordering an
  ;; outbox: the slice now captures trace context into the row it writes, for
  ;; exactly the reason `change_price.clj` has since lab 26. The rule was never
  ;; about the file. It is about `price-order`, which decides a price from two
  ;; values and must not acquire a reason to need anything else.
  (let [source (defn-body "src/lab28/ordering/place_order.clj" "price-order")]
    (is (some? source))
    (is (not (str/includes? source "telemetry")))
    (is (not (str/includes? source "jdbc")))
    (is (not (str/includes? source "now")))))
