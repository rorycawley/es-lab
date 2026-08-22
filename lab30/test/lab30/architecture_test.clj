(ns lab30.architecture-test
  "Small fitness functions for the boundaries this lab claims."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [path] (slurp path))

(deftest one-public-module-api-hides-the-two-slices-test
  (let [api (source "src/lab30/registry/api.clj")]
    (is (str/includes? api "lab30.registry.register-entity"))
    (is (str/includes? api "lab30.registry.search-entities"))
    (is (= #{"api.clj"}
           (reduce (fn [found file]
                     (cond-> found
                       (and (.isFile file)
                            (str/includes? (slurp file) "platform.validation"))
                       (conj (.getName file))))
                   #{}
                   (file-seq (io/file "src/lab30/registry"))))
        "only the public driving edge knows the schema library")))

(deftest postgres-is-the-only-implementation-of-the-search-fold-test
  (let [query  (source "src/lab30/registry/search_entities.clj")
        schema (source "resources/schema.sql")]
    (is (not (str/includes? query "clojure.string/lower-case")))
    (is (not (str/includes? query ".toLowerCase")))
    (is (str/includes? query "registry.search_key"))
    (is (str/includes? query "casefold(normalize"))
    (is (str/includes? schema "registry.search_key"))
    (is (str/includes? schema "casefold(normalize"))))

(deftest legal-form-cannot-pollute-the-name-index-test
  (let [schema (source "resources/schema.sql")
        document (second (re-find #"(?s)CREATE FUNCTION registry.name_document(.*?)CREATE TABLE"
                                  schema))]
    (is (some? document))
    (is (not (str/includes? document "legal_form")))
    (is (str/includes? schema "legal_form      registry.legal_form"))))

(deftest the-cascade-is-visible-as-five-separate-queries-test
  (let [query (source "src/lab30/registry/search_entities.clj")]
    (doseq [rung ["identifier-sql" "exact-name-sql" "prefix-sql"
                  "phrase-sql" "fuzzy-sql"]]
      (is (str/includes? query rung) rung))
    (testing "one giant scored query has not replaced the ladder"
      (is (not (str/includes? query "UNION ALL"))))))

(deftest the-german-exception-is-pure-and-versioned-test
  (let [german (source "src/lab30/registry/german.clj")]
    (is (str/includes? german "(def version"))
    (doseq [effect ["next.jdbc" "java.net" "datasource" "slurp"]]
      (is (not (str/includes? german effect)) effect))))
