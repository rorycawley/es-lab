(ns architecture-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def source-root (io/file "src"))

(defn- clojure-source-files []
  (filter #(and (.isFile %)
                (str/ends-with? (.getName %) ".clj"))
          (file-seq source-root)))

(defn- relative-path [file]
  (str (.relativize (.toPath source-root) (.toPath file))))

(defn- expected-namespace [file]
  (-> (relative-path file)
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace java.io.File/separator ".")
      symbol))

(defn- declared-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (second (read reader)))))

(def forbidden-domain-dependencies
  ["ring."
   "reitit."
   "next.jdbc"
   "cheshire."
   "aero."
   "com.stuartsierra.component"
   "platform."
   "System/currentTimeMillis"
   "random-uuid"])

(def forbidden-application-dependencies
  ["ring."
   "reitit."
   "next.jdbc"
   "platform.http"
   "platform.persistence"
   "cart.adapter.out.persistence"])

(defn- violations [path-fragment forbidden]
  (for [file (clojure-source-files)
        :when (str/includes? (relative-path file) path-fragment)
        dependency forbidden
        :when (str/includes? (slurp file) dependency)]
    [(relative-path file) dependency]))

(deftest namespaces-match-source-paths
  (doseq [file (clojure-source-files)]
    (testing (relative-path file)
      (is (= (expected-namespace file)
             (declared-namespace file))))))

(deftest domain-core-has-no-effectful-dependencies
  (is (empty? (violations (str "cart" java.io.File/separator "domain")
                          forbidden-domain-dependencies))))

(deftest application-shell-does-not-import-concrete-adapters
  (is (empty? (violations (str "cart" java.io.File/separator "application")
                          forbidden-application-dependencies))))
