(ns lab28.search-test
  "What the search box does, through the module APIs.

  Each test names a thing `LIKE` cannot do."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab28.catalog.api :as catalog]
            [lab28.fixture :as fixture]
            [lab28.ordering.api :as ordering]
            [lab28.system :as system]))

(def vanilla   #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def pistachio #uuid "0f1c2b3a-0000-4000-8000-000000000027")
(def chocolate #uuid "0f1c2b3a-0000-4000-8000-000000000028")

(defn- stock! [catalog product-id product-name price-cents description]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id product-id
                                  :product-name product-name
                                  :price-cents price-cents})
  (catalog/describe-product! catalog {:command-id (random-uuid)
                                      :correlation-id (random-uuid)
                                      :product-id product-id
                                      :description description}))

(defn- shelf! [catalog]
  (stock! catalog vanilla "vanilla" 300 "a creamy vanilla flavour with real pods")
  (stock! catalog pistachio "pistachio" 450 "sea salt and roasted nuts")
  (stock! catalog chocolate "chocolate" 350 "dark and bitter, no pistachio at all"))

(defn- names [response]
  (mapv :product-name (:found response)))

;; ---------------------------------------------------------------------------

(deftest search-matches-words-rather-than-substrings-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (shelf! catalog)
      (testing "a query stems to the same lexeme as the text"
        (is (= ["vanilla"] (names (catalog/search catalog {:query "flavours"})))
            "the description says flavour, the query says flavours")
        (is (= ["vanilla"] (names (catalog/search catalog {:query "creamy"})))))
      (testing "and a substring that is not a word is not a match"
        (is (:no-matches (catalog/search catalog {:query "anill"}))
            "LIKE '%anill%' would have matched vanilla")))))

(deftest the-name-outranks-a-mention-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (shelf! catalog)
      (is (= ["pistachio" "chocolate"]
             (names (catalog/search catalog {:query "pistachio"})))
          "the product called pistachio beats the one that merely mentions it"))))

(deftest a-user-can-type-anything-into-it-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (shelf! catalog)
      (testing "quoted words must be adjacent"
        (is (= ["pistachio"] (names (catalog/search catalog {:query "\"sea salt\""}))))
        (is (:no-matches (catalog/search catalog {:query "\"salt sea\""}))))
      (testing "a leading minus excludes"
        (is (= ["chocolate"]
               (names (catalog/search catalog {:query "bitter -vanilla"})))))
      (testing "and punctuation is not a syntax error"
        (is (:no-matches (catalog/search catalog {:query "(("}))
            "websearch_to_tsquery never raises; to_tsquery would have")))))

(deftest a-hit-carries-the-line-it-was-found-in-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (shelf! catalog)
      (let [snippet (:snippet (first (:found (catalog/search catalog {:query "bitter"}))))]
        (is (str/includes? snippet "«bitter»")
            "ts_headline marks the match inside the retained description")))))

(deftest a-misspelling-is-a-suggestion-not-a-result-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (shelf! catalog)
      (testing "no lexeme matches, so full-text search finds nothing"
        (let [response (catalog/search catalog {:query "pistacio"})]
          (is (nil? (:found response)))
          (is (= ["pistachio"] (mapv :product-name (:did-you-mean response)))
              "trigrams compare letters, not words, so the typo is still close")))
      (testing "spelled correctly it is an ordinary result"
        (is (= ["pistachio" "chocolate"]
               (names (catalog/search catalog {:query "pistachio"})))))
      (testing "and a query resembling nothing gets neither"
        (is (= "xylophone" (:no-matches (catalog/search catalog {:query "xylophone"}))))))))

;; ---------------------------------------------------------------------------
;; Two owners, two lists
;; ---------------------------------------------------------------------------

(deftest each-module-searches-only-what-it-owns-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (shelf! catalog)
      (system/relay-catalog! app)
      (ordering/place-order! ordering {:order-id (random-uuid)
                                       :correlation-id (random-uuid)
                                       :product-id pistachio
                                       :quantity 2
                                       :customer-email "ada@example.com"
                                       :payment-method "pm_card_visa"})

      (testing "one word, two independent answers"
        (is (= ["pistachio" "chocolate"]
               (names (catalog/search catalog {:query "pistachio"}))))
        (is (= ["pistachio"]
               (mapv :product-name (:found (ordering/search ordering {:query "pistachio"}))))))

      (testing "Catalog's descriptions are invisible to Ordering's index"
        (is (:no-matches (ordering/search ordering {:query "roasted"}))
            "the description lives in Catalog, and Ordering cannot read it")))))

(deftest the-customer-is-not-searchable-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (shelf! catalog)
      (system/relay-catalog! app)
      (let [order-id (random-uuid)]
        (ordering/place-order! ordering {:order-id order-id
                                         :correlation-id (random-uuid)
                                         :product-id vanilla
                                         :quantity 1
                                         :customer-email "ada@example.com"
                                         :payment-method "pm_card_visa"})
        (is (some? (:found (ordering/get-order ordering {:order-id order-id})))
            "the order exists and Ordering holds the address")
        (doseq [query ["ada" "ada@example.com" "example.com"]]
          (is (= query (:no-matches (ordering/search ordering {:query query})))
              (str "a search box must not be a way to look up people: " query)))))))
