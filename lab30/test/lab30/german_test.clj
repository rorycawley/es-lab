(ns lab30.german-test
  "The pure fallback is tested directly because a pure core is valuable
  precisely because its rule can be exercised without an adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [lab30.registry.german :as german]))

(deftest compounds-are-decomposed-into-searchable-roots-test
  (is (= "vermögen verwaltung gesellschaft"
         (german/parts "Vermögensverwaltungsgesellschaft")))
  (is (= "wasser kraft" (german/parts "Wasserkraft")))
  (is (= "" (german/parts "Société Générale"))
      "ordinary names do not acquire invented German parts"))

(deftest the-word-list-is-an-explicitly-versioned-limit-test
  (testing "known vocabulary can be rebuilt deterministically"
    (is (pos-int? german/version))
    (is (= (german/parts "Vermögensverwaltungsgesellschaft")
           (german/parts "VERMÖGENSVERWALTUNGSGESELLSCHAFT"))))
  (testing "unknown compounds are honestly not split"
    (is (= "" (german/parts "Donaudampfschifffahrtsgesellschaft")))))
