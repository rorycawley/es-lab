(ns lab25.architecture-test
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
  (doseq [path ["src/lab25/catalog/change_price.clj"
                "src/lab25/catalog/get_product.clj"
                "src/lab25/ordering/place_order.clj"
                "src/lab25/ordering/get_order.clj"
                "src/lab25/ordering/catalog_price_changed.clj"]]
    (is (.isFile (io/file path)) (str path " — the use case should be visible")))
  (testing "technical stereotypes are not the top-level structure"
    (is (empty? (filter #(re-find #"/(controllers?|services?|repositories?|entities?)/"
                                  (str %))
                        (clj-files "src/lab25"))))))

(deftest modules-communicate-only-through-public-contracts-test
  (testing "Catalog knows nothing about Ordering"
    (doseq [file (clj-files "src/lab25/catalog")
            required (requires (slurp file))]
      (is (not (str/starts-with? required "lab25.ordering"))
          (str (.getName file) " requires " required))))
  (testing "Ordering may know Catalog's contract and nothing behind it"
    (doseq [file (clj-files "src/lab25/ordering")
            required (filter #(str/starts-with? % "lab25.catalog")
                             (requires (slurp file)))]
      (is (= "lab25.catalog.contract" required)
          (str (.getName file) " bypasses Catalog's public contract via " required)))))

(deftest each-module-owns-its-sql-test
  (doseq [file (clj-files "src/lab25/catalog")]
    (is (not (str/includes? (slurp file) "ordering."))
        (str (.getName file) " reaches into Ordering's schema")))
  (doseq [file (clj-files "src/lab25/ordering")]
    (is (not (re-find #"catalog\.(product|outbox)" (slurp file)))
        (str (.getName file) " reaches into Catalog's tables"))))

(deftest commands-and-queries-do-not-share-a-generic-model-test
  (is (not (.exists (io/file "src/lab25/model.clj"))))
  (doseq [slice ["catalog/change_price.clj" "catalog/get_product.clj"
                 "ordering/place_order.clj" "ordering/get_order.clj"]]
    (let [source (slurp (io/file "src/lab25" slice))]
      (is (str/includes? source "(def Request") (str slice " owns no request shape"))
      (is (re-find #"\(defn handle!?" source) (str slice " owns no handler")))))

(deftest cross-cutting-concerns-wrap-slices-at-the-api-test
  (doseq [api ["src/lab25/catalog/api.clj" "src/lab25/ordering/api.clj"]
          :let [source (slurp (io/file api))]]
    (is (str/includes? source "behaviour/validation"))
    (is (str/includes? source "behaviour/observation"))))

(deftest module-public-surfaces-do-not-expose-database-handles-test
  (doseq [api ["src/lab25/catalog/api.clj" "src/lab25/ordering/api.clj"]
          :let [source (slurp (io/file api))]]
    (is (not (re-find #"\(defrecord\s+\w+\s+\[[^\]]*datasource" source))
        (str api " exposes its database handle through the public module value"))))

(deftest the-database-declares-separate-owners-test
  (let [schema (slurp (io/file "resources/schema.sql"))]
    (is (str/includes? schema "SCHEMA catalog AUTHORIZATION catalog_module"))
    (is (str/includes? schema "SCHEMA ordering AUTHORIZATION ordering_module"))
    (is (str/includes? schema "REVOKE CREATE ON SCHEMA public FROM PUBLIC"))))
