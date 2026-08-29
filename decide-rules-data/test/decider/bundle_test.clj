(ns decider.bundle-test
  "The example `resource-paths` is hand-maintained, and every generic test is
   driven from it: `load-all` feeds the generative suite, so a bundle missing
   from the list is a bundle nothing tests. That is a silent failure, and this
   is what makes it a loud one."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [decider.bundle :as bundle]
   [decider.core :as core]
   [decider.fixtures :as fixtures]))

(defn- bundle-files-on-disk
  "The `.edn` files actually sitting in the resource directory.

   Read from the filesystem rather than the classpath: the point is to catch a
   file someone added, which is a question about the directory, not about what
   happens to be loadable."
  []
  (->> (io/file (io/resource fixtures/resource-directory))
       .listFiles
       (map #(.getName %))
       (filter #(str/ends-with? % ".edn"))
       set))

(deftest every-bundle-file-is-listed-and-every-listing-exists
  (let [listed (set (map #(str/replace % (str fixtures/resource-directory "/") "")
                         fixtures/resource-paths))
        on-disk (bundle-files-on-disk)]
    (testing "no bundle file is missing from resource-paths"
      (is (empty? (remove listed on-disk))
          "add it to decider.fixtures/resource-paths, or nothing will test it"))
    (testing "no listed path is missing from disk"
      (is (empty? (remove on-disk listed))
          "remove it from decider.fixtures/resource-paths"))
    (is (= listed on-disk))))

(deftest resource-paths-has-no-duplicates
  (is (= (count fixtures/resource-paths)
         (count (set fixtures/resource-paths)))))

(deftest load-all-loads-every-listed-bundle
  (let [loaded (fixtures/load-all)]
    (is (= (count fixtures/resource-paths) (count loaded)))
    (testing "and each has a distinct identity"
      (is (= (count loaded) (count (set (map :spec/id loaded)))))
      (is (= (count loaded) (count (set (map :spec/hash loaded))))))))

(deftest a-missing-resource-says-which-one
  (let [thrown (try
                 (bundle/load "semantic-bundles/does-not-exist.edn")
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown))
    (is (= "semantic-bundles/does-not-exist.edn"
           (:resource-path (ex-data thrown))))))

;; Fixtures live in `test/resources/bundle-fixtures/` on the test classpath.
;; Writing them into `test/resources/semantic-bundles/` at test time would race
;; with the listing test above, which is exactly the kind of tidy idea that
;; makes a suite flaky.

(deftest a-file-holding-more-than-one-form-is-refused
  ;; `edn/read-string` returns the first form and drops the rest without
  ;; complaint, so a bundle file with a stray second map — a bad merge, a
  ;; paste — would have loaded as whichever came first, silently.
  (let [thrown (try (bundle/load "bundle-fixtures/two-forms.edn")
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown))
    (is (str/includes? (ex-message thrown) "more than one form"))
    (is (= "bundle-fixtures/two-forms.edn" (:resource-path (ex-data thrown))))))

(deftest an-empty-file-is-refused
  ;; A file of nothing but comments reads as end-of-input, which `read-string`
  ;; would have turned into nil and passed along as a bundle.
  (let [thrown (try (bundle/load "bundle-fixtures/empty.edn")
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown))
    (is (str/includes? (ex-message thrown) "empty"))))

(deftest load-prepared-is-load-then-prepare
  ;; Same answer, one validation instead of two. It must not become a second
  ;; way of building a prepared specification.
  (doseq [path fixtures/resource-paths]
    (testing path
      (is (= (:prepared/specification (core/prepare (bundle/load path)))
             (:prepared/specification (bundle/load-prepared path))))
      (is (= (:prepared/ref (core/prepare (bundle/load path)))
             (:prepared/ref (bundle/load-prepared path)))))))
