(ns decider.validation-test
  "What `decider.schema/problems` refuses, and what it says about it.

   The bundle validator is the largest and subtlest part of the project and
   nothing exercised it in the negative. These tests are the difference between
   a validator that is believed to work and one that is known to."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.dsl :as dsl]
   [decider.fixtures :as fixtures]
   [decider.schema :as schema]))

(def base
  (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn"))

(defn- kinds
  [specification]
  (set (map :problem (schema/problems specification))))

(deftest the-language-the-validator-accepts-is-the-language-the-interpreter-runs
  ;; README section 45's invariant, checked rather than asserted. Every
  ;; operator `schema/operand-counts` admits must be one `decider.dsl`
  ;; implements, or a bundle passes validation and then fails to run.
  (doseq [operator (keys schema/operand-counts)]
    (testing (str operator)
      (let [form    (into [operator] (repeat (schema/operand-counts operator) nil))
            ;; nil operands make the arithmetic operators throw, which is fine:
            ;; the only outcome under test is whether the operator is known.
            outcome (try
                      (dsl/expression-value {} form)
                      :implemented
                      (catch clojure.lang.ExceptionInfo e (ex-message e))
                      (catch Exception _ :implemented))]
        (is (not= "Unknown expression operator" outcome))))))

(deftest structural-problems-are-detected
  (is (contains? (kinds (update base :rules conj (first (:rules base))))
                 :duplicate-rule-ids))
  (is (contains? (kinds (update base :derive conj (first (:derive base))))
                 :duplicate-derived-names))
  (is (contains? (kinds (assoc base :derive [[:a [:expr/get :derived [:b]]]
                                             [:b [:expr/get :state [:tickets-remaining]]]]))
                 :undefined-derived-reference))
  (is (contains? (kinds (assoc-in base [:events 0 :data :x] [:expr/get :derived [:nope]]))
                 :undefined-derived-reference)))

(deftest expression-problems-say-what-is-wrong-and-where
  (testing "an unknown operator names itself"
    (let [problem (->> (schema/problems (assoc-in base [:rules 0 :require] [:expr/wat 1 2]))
                       (filter (comp #{:unknown-operator} :problem))
                       first)]
      (is (= :expr/wat (:operator problem)))
      (is (= [:rules :BR-1] (:in problem)))
      (is (contains? (:known problem) :expr/get))))

  (testing "wrong arity reports both counts"
    (let [problem (->> (schema/problems (assoc-in base [:rules 0 :require] [:expr/+ 1]))
                       (filter (comp #{:wrong-operand-count} :problem))
                       first)]
      (is (= {:operator :expr/+ :expected 2 :actual 1} (select-keys problem [:operator :expected :actual])))
      (is (= [:rules :BR-1] (:in problem)))))

  (testing "an unknown source lists the sources that exist"
    (let [problem (->> (schema/problems (assoc-in base [:rules 0 :require] [:expr/get :database [:x]]))
                       (filter (comp #{:unknown-source} :problem))
                       first)]
      (is (= :database (:source problem)))
      (is (= schema/sources (:known problem)))))

  (testing "a problem inside a derivation names the derivation"
    (let [problem (->> (schema/problems (assoc base :derive [[:total [:expr/+ 1]]]))
                       (filter (comp #{:wrong-operand-count} :problem))
                       first)]
      (is (= [:derive :total] (:in problem)))))

  (testing "a problem inside an event template names the index"
    (let [problem (->> (schema/problems (assoc-in base [:events 0 :data :x] [:expr/wat]))
                       (filter (comp #{:unknown-operator} :problem))
                       first)]
      (is (= [:events 0] (:in problem))))))

(deftest every-event-template-must-render-to-a-map
  (let [expression-event (assoc base :events
                                [[:expr/get :state [:tickets-remaining]]])]
    (is (contains? (kinds expression-event) :invalid-semantic-bundle))
    (is (thrown? clojure.lang.ExceptionInfo
                 (decider/prepare expression-event)))))

(deftest a-broken-embedded-schema-explains-itself
  (let [problem (->> (schema/problems (assoc base :state/schema [:map [:x :not-a-real-schema]]))
                     (filter (comp #{:invalid-malli-schema} :problem))
                     first)]
    (is (= :state/schema (:schema/key problem)))
    (is (= :malli.core/invalid-schema (:reason problem)))
    (is (string? (:message problem)))
    (testing "and names the sub-form that is not a schema"
      (is (re-find #"not-a-real-schema" (:offending-form problem))))))

(deftest problems-are-plain-data
  ;; They get logged. A Malli schema object or an exception in there means the
  ;; report cannot be serialized where it is most needed.
  (let [reported (schema/problems (assoc base :state/schema [:map [:x :nope]]))]
    (is (seq reported))
    (is (= reported (edn/read-string (pr-str reported))))))

;; ---------------------------------------------------------------------------
;; Guard rules — README section 16
;; ---------------------------------------------------------------------------

(def amazon
  (bundle/load "semantic-bundles/amazon-add-item.edn"))

(def missing-product-state
  {:basket-id "b" :status :open :sku->product {} :sku->quantity {}})

(def add-missing-product
  {:command/type :add-item :data {:sku "MISSING" :quantity 1}})

(defn- reordered
  [specification rule-ids]
  (let [by-id (into {} (map (juxt :rule/id identity)) (:rules specification))]
    (assoc specification :rules (mapv by-id rule-ids))))

(deftest a-guard-rule-must-precede-the-rules-that-depend-on-it
  (testing "as shipped, the guard runs first and the answer is the true one"
    (is (= :product-not-found
           (get-in (decider/prepare-and-decide amazon missing-product-state add-missing-product)
                   [:decision :reason]))))

  (testing "moving the guard after a rule that reads the product is refused"
    ;; Before :rule/after existed this reorder was accepted and turned a clean
    ;; rejection into a NullPointerException.
    (let [broken (reordered amazon [:BR-1 :BR-5 :BR-2 :BR-3 :BR-4 :BR-6])]
      (is (contains? (kinds broken) :guard-rule-out-of-order))
      (is (thrown? clojure.lang.ExceptionInfo (decider/prepare broken)))))

  (testing "moving the guard to the end is refused too"
    ;; This one did not crash. It answered :product-not-purchasable for a
    ;; product that does not exist, which is worse than crashing.
    (let [broken (reordered amazon [:BR-1 :BR-3 :BR-4 :BR-5 :BR-6 :BR-2])]
      (is (contains? (kinds broken) :guard-rule-out-of-order))))

  (testing "a guard that does not exist is refused"
    (is (contains? (kinds (assoc-in base [:rules 0 :rule/after] [:BR-99]))
                   :unknown-guard-rule)))

  (testing "a rule cannot guard itself"
    (is (contains? (kinds (assoc-in base [:rules 0 :rule/after] [:BR-1]))
                   :guard-rule-out-of-order))))

;; ---------------------------------------------------------------------------
;; Unknown keys — the schemas this project writes about itself are closed
;; ---------------------------------------------------------------------------

(deftest a-typo-cannot-silently-disable-a-guard
  ;; The failure this prevents: `:rule/aftr` used to validate cleanly while the
  ;; guard it was meant to declare simply did not exist, so the reordering
  ;; `:rule/after` was added to catch went through and crashed at runtime.
  (let [typoed (-> amazon
                   (assoc-in [:rules 2 :rule/aftr] [:BR-2])
                   (update-in [:rules 2] dissoc :rule/after))
        problem (->> (schema/problems typoed)
                     (filter (comp #{:unknown-rule-key} :problem))
                     first)]
    (is (some? problem))
    (testing "and it names the rule rather than a position in a vector"
      (is (= :BR-3 (:rule/id problem)))
      (is (= #{:rule/aftr} (:keys problem)))
      (is (= schema/rule-keys (:known problem))))
    (is (thrown? clojure.lang.ExceptionInfo (decider/prepare typoed)))))

(deftest unknown-keys-are-refused-wherever-this-project-owns-the-shape
  (testing "on the bundle"
    (is (contains? (kinds (assoc base :rulez [])) :invalid-semantic-bundle)))
  (testing "on :rule-evaluation"
    (is (contains? (kinds (assoc base :rule-evaluation
                                 {:strategy :first-failure :stratergy :x}))
                   :invalid-semantic-bundle)))
  (testing "but :spec/hash is expected, because load and prepare attach it"
    (is (empty? (schema/problems (assoc base :spec/hash "sha256:whatever"))))))

;; ---------------------------------------------------------------------------
;; Expressions in template keys — README section 28
;; ---------------------------------------------------------------------------

(deftest an-expression-in-a-template-key-is-refused
  ;; `template-value` renders a map's values and copies its keys through
  ;; untouched, so an expression in key position lands in the event as the
  ;; literal vector. Refused rather than silently produced.
  (testing "as the key itself"
    (let [problem (->> (schema/problems
                        (assoc-in base [:events 0 :data]
                                  {[:expr/get :state [:performance-id]] 1}))
                       (filter (comp #{:expression-in-template-key} :problem))
                       first)]
      (is (= [:expr/get :state [:performance-id]] (:key problem)))
      (is (= [:events 0] (:in problem)))))

  (testing "nested inside the key"
    (is (contains? (kinds (assoc-in base [:events 0 :data]
                                    {[:a [:expr/get :state [:x]]] 1}))
                   :expression-in-template-key)))

  (testing "and in a derivation, which renders the same way"
    (is (contains? (kinds (assoc base :derive
                                 [[:total {[:expr/get :state [:x]] 1}]]))
                   :expression-in-template-key)))

  (testing "ordinary keys are still ordinary"
    (is (empty? (schema/problems base)))))

;; ---------------------------------------------------------------------------
;; problems reports; it does not throw
;; ---------------------------------------------------------------------------

(deftest a-structurally-malformed-bundle-is-reported-not-thrown-at
  ;; The detail checks read a bundle's parts as the shapes they should be. When
  ;; they are not, the caller must still get a report: an IllegalArgumentException
  ;; out of a destructuring form is not a validation result.
  (doseq [[k value] [[:derive [1 2 3]]
                     [:derive "nope"]
                     [:derive {:a 1}]
                     [:derive nil]
                     [:rules  "nope"]
                     [:rules  [1 2]]
                     [:rules  (assoc-in (:rules base) [0 :rule/after] 1)]
                     [:rules  (assoc-in (:rules base) [0 :require]
                                        [:expr/get :derived 1])]
                     [:events 5]
                     [:events "nope"]]]
    (testing (str k " is " (pr-str value))
      (is (vector? (schema/problems (assoc base k value))))))

  (testing "and the bundle itself may be anything at all"
    (doseq [value [nil 5 "nope" [1 2 3] #{:a}]]
      (is (vector? (schema/problems value))))))

(defspec any-value-in-any-key-is-reported-not-thrown-at 300
  (prop/for-all [k (gen/elements [:derive :rules :events :spec/id :spec/version
                                  :rule-evaluation :state/schema :command/schema])
                 v gen/any-printable]
    (vector? (schema/problems (assoc base k v)))))

;; ---------------------------------------------------------------------------
;; Depth
;; ---------------------------------------------------------------------------

(deftest a-bundle-too-large-to-walk-is-refused-rather-than-walked
  ;; Depth alone does not bound a bundle. This one nests three levels and would
  ;; have validated: two hundred thousand keys, every one of them walked by
  ;; canonicalization, hashing and template checking.
  (let [wide (assoc base :events
                    [(into {:event/type :e}
                           (map #(vector (keyword (str "k" %)) %))
                           (range 200000))])]
    (is (= [:specification-too-large] (map :problem (schema/problems wide))))
    (is (thrown? clojure.lang.ExceptionInfo (decider/prepare wide))))

  (testing "and real bundles are nowhere near the limit"
    (doseq [specification (fixtures/load-all)]
      (is (empty? (schema/problems specification))))))

(deftest a-bundle-too-deep-to-walk-is-refused-rather-than-walked
  (let [deep (reduce (fn [acc _] [acc]) [:x] (range 20000))]
    (is (= [:specification-too-deep]
           (map :problem (schema/problems (assoc base :events [deep])))))
    (testing "and prepare refuses it without overflowing the stack"
      (is (thrown? clojure.lang.ExceptionInfo
                   (decider/prepare (assoc base :events [deep])))))))
