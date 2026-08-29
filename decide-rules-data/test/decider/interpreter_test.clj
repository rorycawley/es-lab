(ns decider.interpreter-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.dsl :as dsl]
   [decider.schema :as schema]
   [malli.core :as m]))

(def ticketmaster
  (bundle/load-prepared "semantic-bundles/ticketmaster-reserve-tickets.edn"))

(def ticketmaster-state
  {:performance-id "oasis-dublin-2026"
   :sale-status :open
   :tickets-remaining 100
   :max-tickets-per-customer 4
   :customer-id->tickets-reserved {"customer-1" 2}})

(defn- reserve
  [quantity]
  (decider/decide ticketmaster
                  ticketmaster-state
                  {:command/type :reserve-tickets
                   :data {:customer-id "customer-1"
                          :quantity quantity}}))

(deftest every-bundle-is-valid
  (doseq [specification (bundle/load-all)]
    (is (empty? (schema/problems specification)))
    (is (string? (:spec/hash specification)))
    (is (str/starts-with? (:spec/hash specification) "sha256:"))))

(deftest malformed-command-is-not-a-business-rejection
  (let [result (reserve "three")]
    (is (= :invalid-command (:result/type result)))
    (is (nil? (:decision result)))
    (is (m/validate schema/Result result))
    (is (m/validate schema/InvalidInput result))))

(deftest malformed-state-is-not-a-business-rejection
  (let [result (decider/decide ticketmaster
                               (assoc ticketmaster-state :tickets-remaining "lots")
                               {:command/type :reserve-tickets
                                :data {:customer-id "customer-1" :quantity 1}})]
    (is (= :invalid-state (:result/type result)))
    (is (nil? (:decision result)))
    (is (m/validate schema/Result result))))

(deftest valid-command-can-be-rejected-by-business-rule
  (let [result (reserve 3)]
    (is (= :decision (:result/type result)))
    (is (= :rejected (get-in result [:decision :decision/type])))
    (is (= :BR-4 (get-in result [:decision :rule/id])))
    (is (= :ticket-limit-exceeded (get-in result [:decision :reason])))
    (is (m/validate schema/Decision (:decision result)))
    (is (m/validate schema/Result result))))

(deftest accepted-decisions-validate-too
  (let [result (reserve 1)]
    (is (= :accepted (get-in result [:decision :decision/type])))
    (is (m/validate schema/Result result))))

(deftest first-failure-semantics-are-explicit
  (let [specification (bundle/load "semantic-bundles/ebay-place-bid.edn")
        state {:auction-id "auction-1"
               :status :closed
               :seller-id "seller-1"
               :starting-price 10000
               :minimum-increment 500}
        command {:command/type :place-bid
                 :data {:bidder-id "seller-1"
                        :amount 1}}
        result (decider/prepare-and-decide specification state command)]
    (testing "BR-1 wins because :first-failure is the declared strategy"
      (is (= :first-failure
             (get-in specification [:rule-evaluation :strategy])))
      (is (= :BR-1 (get-in result [:decision :rule/id]))))))

(deftest the-convenience-path-is-the-same-path
  ;; `prepare-and-decide` must be exactly `prepare` then `decide`, not a second
  ;; implementation that could drift.
  (let [raw (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn")
        command {:command/type :reserve-tickets
                 :data {:customer-id "customer-1" :quantity 3}}]
    (is (= (decider/prepare-and-decide raw ticketmaster-state command)
           (decider/decide (decider/prepare raw) ticketmaster-state command)))))

(deftest every-collection-the-interpreter-produces-is-a-vector
  ;; `:expr/values` used to yield a `vals` seq, which prints as `(a b)` and
  ;; reads back as a list where every other collection in a decision is a
  ;; vector — a difference `decider.hash/canonical` treats as real.
  (is (vector? (dsl/expression-value {:state {:m {:a 1 :b 2}}}
                                     [:expr/values [:expr/get :state [:m]]])))
  (testing "and an absent map gives an empty vector, not nil"
    (is (= [] (dsl/expression-value {:state {}}
                                    [:expr/values [:expr/get :state [:missing]]]))))
  (testing "which does not change how the Secret Santa bundle decides"
    (let [santa (bundle/load-prepared "semantic-bundles/secret-santa-assign-recipient.edn")
          result (decider/decide santa
                                 {:exchange-id "x" :status :assigning
                                  :participant-ids #{"a" "b"}
                                  :giver-id->recipient-id {}
                                  :giver-id->excluded-recipient-ids {}}
                                 {:command/type :assign-recipient
                                  :data {:giver-id "a" :recipient-id "b"}})]
      (is (= :accepted (get-in result [:decision :decision/type]))))))

(deftest prepare-is-idempotent
  ;; The asymmetry is deliberate: `decide` refuses anything unprepared, so
  ;; `prepare` must be safe to call on something already prepared — otherwise
  ;; a boundary that prepares defensively has to know what it was handed.
  (let [raw (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn")
        once (decider/prepare raw)]
    (is (identical? once (decider/prepare once)))
    (is (= (decider/decide once ticketmaster-state
                           {:command/type :reserve-tickets
                            :data {:customer-id "customer-1" :quantity 3}})
           (decider/decide (decider/prepare once) ticketmaster-state
                           {:command/type :reserve-tickets
                            :data {:customer-id "customer-1" :quantity 3}})))))

(deftest a-prepared-specification-cannot-be-forged-or-rewritten
  (let [raw      (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn")
        prepared (decider/prepare raw)]
    (testing "a marker key does not impersonate the output of prepare"
      (is (not (decider/prepared? {:prepared/specification raw}))))
    (testing "the specification cannot diverge from its cached identity"
      (is (thrown? ClassCastException
                   (assoc-in prepared
                             [:prepared/specification :rules 0 :require]
                             false))))))

(deftest decide-refuses-a-raw-bundle-and-says-what-to-do
  ;; `decide` used to accept either, which made the call that re-hashes and
  ;; re-validates the whole bundle the one that looked normal. Now it is a
  ;; refusal, and the refusal names both alternatives — without the check, a raw
  ;; bundle reached `(nil state)` and failed as an unexplained NPE.
  (let [raw (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn")
        thrown (try (decider/decide raw ticketmaster-state
                                    {:command/type :reserve-tickets
                                     :data {:customer-id "customer-1" :quantity 1}})
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown))
    (is (str/includes? (ex-message thrown) "decider.core/prepare"))
    (is (str/includes? (ex-message thrown) "prepare-and-decide"))
    (testing "and it says which bundle, since that is what you have in hand"
      (is (= :ticketmaster/reserve-tickets (:spec/id (ex-data thrown)))))))

(deftest a-specification-the-interpreter-cannot-run-throws-with-context
  ;; README section 11's fourth outcome. The DSL is untyped, so a bundle can be
  ;; well formed and still ask for something impossible. What matters is that
  ;; the failure names the rule instead of arriving as a bare NPE from
  ;; somewhere inside clojure.core.
  (let [impossible
        {:spec/id :interpreter-test/impossible
         :spec/version 1
         :rule-evaluation {:strategy :first-failure}
         ;; :absent is not in the state schema, and Malli maps are open
         ;; (README section 17), so this state validates and the lookup is nil.
         :state/schema [:map [:limit :int]]
         :command/schema [:map [:command/type [:enum :go]] [:data [:map [:n :int]]]]
         :derive []
         :rules [{:rule/id :BR-1
                  :rule/text "The total must be within the limit."
                  :require [:expr/<= [:expr/get :state [:absent]]
                            [:expr/get :state [:limit]]]
                  :otherwise :over-limit}]
         :events [{:event/type :went :data {}}]}]
    (testing "the bundle itself is well formed"
      (is (empty? (schema/problems impossible))))

    (let [thrown (try
                   (decider/prepare-and-decide impossible {:limit 10}
                                               {:command/type :go :data {:n 1}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (is (str/starts-with? (ex-message thrown) "Specification failed"))
      (testing "and it says which rule, and under which specification"
        (is (= :BR-1 (:rule/id (ex-data thrown))))
        (is (= :interpreter-test/impossible (get-in (ex-data thrown) [:spec/ref :id])))
        (is (some? (ex-cause thrown)))))))
