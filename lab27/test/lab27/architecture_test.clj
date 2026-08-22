(ns lab27.architecture-test
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
  (doseq [path ["src/lab27/catalog/change_price.clj"
                "src/lab27/catalog/get_product.clj"
                "src/lab27/ordering/place_order.clj"
                "src/lab27/ordering/get_order.clj"
                "src/lab27/ordering/catalog_price_changed.clj"]]
    (is (.isFile (io/file path)) (str path " — the use case should be visible")))
  (testing "technical stereotypes are not the top-level structure"
    (is (empty? (filter #(re-find #"/(controllers?|services?|repositories?|entities?)/"
                                  (str %))
                        (clj-files "src/lab27"))))))

(deftest modules-communicate-only-through-public-contracts-test
  (testing "Catalog knows nothing about Ordering"
    (doseq [file (clj-files "src/lab27/catalog")
            required (requires (slurp file))]
      (is (not (str/starts-with? required "lab27.ordering"))
          (str (.getName file) " requires " required))))
  (testing "Ordering may know Catalog's contract and nothing behind it"
    (doseq [file (clj-files "src/lab27/ordering")
            required (filter #(str/starts-with? % "lab27.catalog")
                             (requires (slurp file)))]
      (is (= "lab27.catalog.contract" required)
          (str (.getName file) " bypasses Catalog's public contract via " required)))))

(deftest each-module-owns-its-sql-test
  (doseq [file (clj-files "src/lab27/catalog")]
    (is (not (str/includes? (slurp file) "ordering."))
        (str (.getName file) " reaches into Ordering's schema")))
  (doseq [file (clj-files "src/lab27/ordering")]
    (is (not (re-find #"catalog\.(product|outbox)" (slurp file)))
        (str (.getName file) " reaches into Catalog's tables"))))

(deftest commands-and-queries-do-not-share-a-generic-model-test
  (is (not (.exists (io/file "src/lab27/model.clj"))))
  (doseq [slice ["catalog/change_price.clj" "catalog/get_product.clj"
                 "ordering/place_order.clj" "ordering/get_order.clj"]]
    (let [source (slurp (io/file "src/lab27" slice))]
      (is (str/includes? source "(def Request") (str slice " owns no request shape"))
      (is (re-find #"\(defn handle!?" source) (str slice " owns no handler")))))

(deftest cross-cutting-concerns-wrap-slices-at-the-api-test
  (doseq [api ["src/lab27/catalog/api.clj" "src/lab27/ordering/api.clj"]
          :let [source (slurp (io/file api))]]
    (is (str/includes? source "behaviour/validation"))
    (is (str/includes? source "behaviour/observation"))))

(deftest module-public-surfaces-do-not-expose-database-handles-test
  (doseq [api ["src/lab27/catalog/api.clj" "src/lab27/ordering/api.clj"]
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
  (doseq [file (clj-files "src/lab27")
          :let [path (str file)]
          :when (not (or (str/ends-with? path "platform/telemetry.clj")
                         (str/ends-with? path "system.clj")))]
    (is (not (re-find telemetry-libraries (slurp file)))
        (str path " names a telemetry library directly"))))

(deftest the-two-that-do-take-one-half-each-test
  (testing "producing telemetry"
    (let [source (slurp (io/file "src/lab27/platform/telemetry.clj"))]
      (is (str/includes? source "clj-otel.api"))
      (is (not (str/includes? source "clj-otel.sdk"))
          "how telemetry is produced is not where it is sent")))
  (testing "configuring where it goes"
    (let [source (slurp (io/file "src/lab27/system.clj"))]
      (is (str/includes? source "clj-otel.sdk"))
      (is (not (re-find #"clj-otel\.api" source))))))

(deftest the-collectors-tests-read-are-not-an-application-dependency-test
  ;; The same rule lab 24 applied to its identity provider: a thing that exists
  ;; to make assertions possible does not get to ship.
  (doseq [file (clj-files "src/lab27")]
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
  (doseq [path ["src/lab27/catalog/search_products.clj"
                "src/lab27/ordering/search_orders.clj"]]
    (is (.isFile (io/file path))
        (str path " -- each module searches what it owns"))))

(deftest nothing-can-rank-one-owner-against-the-other-test
  ;; The cost this lab accepts. Two indexes in two schemas cannot produce one
  ;; ranked list, and the only place allowed to hold both answers at once is
  ;; the composition root -- so no module can quietly grow a cross-owner query.
  (doseq [file (clj-files "src/lab27")
          :let [required (requires (slurp file))]
          :when (not (str/ends-with? (str file) "system.clj"))]
    (is (not (and (contains? required "lab27.catalog.api")
                  (contains? required "lab27.ordering.api")))
        (str file " reaches into both modules' public APIs"))))

(deftest the-customer-is-not-in-any-search-index-test
  (let [schema (slurp (io/file "resources/schema.sql"))
        generated (re-seq #"(?s)GENERATED ALWAYS AS \((.*?)\) STORED" schema)]
    (is (= 2 (count generated)) "two derived vectors, one per module")
    (doseq [[_ expression] generated]
      (is (not (str/includes? expression "customer_email"))
          "a trigram index over an address is a people search"))))

(deftest the-plan-harness-is-not-an-application-dependency-test
  (doseq [file (clj-files "src/lab27")]
    (is (not (str/includes? (slurp file) "EXPLAIN"))
        (str file " -- measuring the planner is a test concern"))))

(deftest the-pure-rule-stays-pure-test
  ;; Lab 0's criterion, still holding at lab 26: `price-order` decides a price
  ;; from values, and giving it a way to emit a span would give it a way to
  ;; need one.
  (let [source (slurp (io/file "src/lab27/ordering/place_order.clj"))]
    (is (not (str/includes? source "telemetry")))))
