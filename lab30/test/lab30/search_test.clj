(ns lab30.search-test
  "The lookup cascade through Registry's public API."
  (:require [clojure.test :refer [deftest is testing]]
            [lab30.fixture :as fixture]
            [lab30.registry.api :as registry]))

(defn- search [module query language]
  (registry/search module {:query query :ui-language language}))

(deftest identifiers-win-before-language-analysis-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Société Générale"
                         {:registration-number "CH-020.3.123.456-7"
                          :euid "CHEU.123456"})
      (doseq [identifier ["CH-020.3.123.456-7" "CHEU.123456"]]
        (let [answer (search module identifier :fr)]
          (is (= :registration (:rung answer)))
          (is (= ["Société Générale"] (fixture/names answer))))))))

(deftest unicode-casefold-and-normalisation-power-exact-lookup-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Straße 1" {:filing-language :de})
      (is (= :exact-name (:rung (search module "Strasse 1" :de))))
      (is (= ["Straße 1"] (fixture/names (search module "STRASSE 1" :de))))

      (fixture/register! module "Müller" {:filing-language :de})
      (is (= ["Müller"]
             (fixture/names (search module "Mu\u0308ller" :de)))
          "an NFD query finds an NFC filed name"))))

(deftest legal-form-is-rendered-not-indexed-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Muster" {:legal-form :plc :filing-language :de})
      (doseq [[language typed expected]
              [[:de "Muster AG" "Muster AG"]
               [:fr "Muster SA" "Muster SA"]
               [:it "Muster SpA" "Muster SpA"]]]
        (let [answer (search module typed language)]
          (is (= :exact-name (:rung answer)))
          (is (= [expected] (mapv :display-name (:found answer)))))))))

(deftest phrase-search-crosses-filing-and-query-languages-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Société Générale" {:filing-language :fr})
      (fixture/register! module "ÉTAT Conseil" {:filing-language :fr})
      (fixture/register! module "Società Anonima d’Italia" {:filing-language :it})
      (doseq [[query expected]
              [["Societe Generale" "Société Générale"]
               ["Etat Conseil" "ÉTAT Conseil"]
               ["Societa Anonima" "Società Anonima d’Italia"]]]
        (let [answer (search module query :de)]
          (is (= :phrase (:rung answer)) query)
          (is (= [expected] (fixture/names answer)) query))))))

(deftest german-compounds-are-searchable-by-their-parts-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Vermögensverwaltungsgesellschaft"
                         {:filing-language :de})
      (let [answer (search module "Verwaltung" :de)]
        (is (= :phrase (:rung answer)))
        (is (= ["Vermögensverwaltungsgesellschaft"] (fixture/names answer)))))))

(deftest punctuation-and-accents-converge-in-the-fuzzy-key-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "L’Oréal" {:filing-language :fr})
      (let [answer (search module "L'Oreal" :it)]
        (is (contains? #{:phrase :fuzzy} (:rung answer)))
        (is (= ["L’Oréal"] (fixture/names answer)))))))

(deftest strict-word-fuzziness-finds-a-typo-inside-a-long-name-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Bäckerei Müller und Söhne"
                         {:filing-language :de})
      (let [answer (search module "Muler" :de)]
        (is (= :fuzzy (:rung answer)))
        (is (= ["Bäckerei Müller und Söhne"] (fixture/names answer)))))))

(deftest prefix-is-cheap-and-short-fuzzy-search-is-refused-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Fratelli D’Angelo" {:filing-language :it})
      (testing "prefix runs before linguistic search"
        (is (= :prefix (:rung (search module "Frat" :it)))))
      (testing "one character never reaches the trigram rung"
        (is (= "x" (:no-matches (search module "x" :it))))))))

(deftest removed-entities-are-absent-from-every-rung-test
  (fixture/with-registry
    (fn [module]
      (fixture/register! module "Gone Company" {:status :removed})
      (doseq [query ["Gone Company" "Gone" "Gon Company"]]
        (is (= query (:no-matches (search module query :fr))))))))
