(ns cart.application.command-pipeline-test
  (:require [cart.application.command-pipeline :as pipeline]
            [clojure.test :refer [deftest is testing]]))

(defn- recording-step [calls label result-fn]
  (fn [context]
    (swap! calls conj label)
    (result-fn context)))

(defn- successful-steps [calls observation-required?]
  {:validate-input
   (recording-step calls :validate
                   #(pipeline/proceed
                     (assoc % :command/observation-required?
                            observation-required?)))
   :resolve-replay
   (recording-step calls :replay pipeline/proceed)
   :check-observation
   (recording-step calls :observation pipeline/proceed)
   :apply-business-rules
   (recording-step calls :business
                   #(pipeline/outcome :success {:command %}))})

(deftest existing-cart-command-runs-the-fixed-order
  (let [calls  (atom [])
        result (pipeline/evaluate (successful-steps calls true)
                                  {:request-id :r1})]
    (is (= [:validate :replay :observation :business] @calls))
    (is (= :success (:outcome result)))
    (is (= :r1 (get-in result [:data :command :request-id])))))

(deftest first-addition-skips-only-observation-currency
  (let [calls  (atom [])
        result (pipeline/evaluate (successful-steps calls false)
                                  {:request-id :r1})]
    (is (= [:validate :replay :business] @calls))
    (is (= :success (:outcome result)))))

(deftest invalid-input-stops-before-every-later-step
  (let [calls (atom [])
        steps (assoc (successful-steps calls true)
                     :validate-input
                     (recording-step calls :validate
                                     (fn [_]
                                       (pipeline/outcome :invalid
                                                         {:code :invalid-input}))))]
    (is (= {:outcome :invalid :data {:code :invalid-input}}
           (pipeline/evaluate steps {})))
    (is (= [:validate] @calls))))

(deftest replay-wins-before-observation-currency
  (let [calls (atom [])
        steps (assoc (successful-steps calls true)
                     :resolve-replay
                     (recording-step calls :replay
                                     (fn [_]
                                       (pipeline/outcome :success
                                                         {:replayed? true}))))]
    (is (= {:outcome :success :data {:replayed? true}}
           (pipeline/evaluate steps {})))
    (is (= [:validate :replay] @calls))))

(deftest request-id-misuse-is-invalid-before-currency
  (let [calls (atom [])
        steps (assoc (successful-steps calls true)
                     :resolve-replay
                     (recording-step calls :replay
                                     (fn [_]
                                       (pipeline/outcome :invalid
                                                         {:code :request-id-reused}))))]
    (is (= :invalid (:outcome (pipeline/evaluate steps {}))))
    (is (= [:validate :replay] @calls))))

(deftest stale-observation-wins-before-business-rejection
  (let [calls (atom [])
        steps (assoc (successful-steps calls true)
                     :check-observation
                     (recording-step calls :observation
                                     (fn [_]
                                       (pipeline/outcome :conflict
                                                         {:code :cart-changed}))))]
    (is (= :conflict (:outcome (pipeline/evaluate steps {}))))
    (is (= [:validate :replay :observation] @calls))))

(deftest current-observation-reaches-business-rejection
  (let [calls (atom [])
        steps (assoc (successful-steps calls true)
                     :apply-business-rules
                     (recording-step calls :business
                                     (fn [_]
                                       (pipeline/outcome :rejected
                                                         {:code :cart-closed}))))]
    (is (= :rejected (:outcome (pipeline/evaluate steps {}))))
    (is (= [:validate :replay :observation :business] @calls))))

(deftest pipeline-rejects-missing-and-malformed-step-results
  (testing "all four steps are required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires every step"
                          (pipeline/evaluate {} {}))))
  (testing "steps cannot silently return arbitrary data"
    (let [steps (assoc (successful-steps (atom []) true)
                       :validate-input (constantly {:unexpected true}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invalid result"
                            (pipeline/evaluate steps {}))))))

(deftest outcome-constructor-allows-only-swr-008-categories
  (is (= {:outcome :success} (pipeline/outcome :success)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown command outcome"
                        (pipeline/outcome :timeout))))

(deftest proceed-context-is-threaded-through-every-applicable-step
  (let [steps  {:validate-input
                #(pipeline/proceed
                  (assoc %
                         :validated? true
                         :command/observation-required? true))
                :resolve-replay
                #(pipeline/proceed (assoc % :replay-checked? true))
                :check-observation
                #(pipeline/proceed (assoc % :observation-current? true))
                :apply-business-rules
                #(pipeline/outcome :success %)}
        result (pipeline/evaluate steps {:request-id :r1})]
    (is (= {:request-id :r1
            :validated? true
            :command/observation-required? true
            :replay-checked? true
            :observation-current? true}
           (:data result)))))

(deftest overlap-cases-prove-the-fixed-precedence
  (testing "unauthentic plus stale is invalid"
    (let [calls (atom [])
          steps (assoc (successful-steps calls true)
                       :validate-input
                       (recording-step
                        calls :validate
                        (fn [_]
                          (pipeline/outcome :invalid
                                            {:code :invalid-observation}))))]
      (is (= :invalid (:outcome (pipeline/evaluate steps {}))))
      (is (= [:validate] @calls))))

  (testing "accepted replay plus stale is success"
    (let [calls (atom [])
          steps (assoc (successful-steps calls true)
                       :resolve-replay
                       (recording-step
                        calls :replay
                        (fn [_]
                          (pipeline/outcome :success {:replayed? true}))))]
      (is (= :success (:outcome (pipeline/evaluate steps {}))))
      (is (= [:validate :replay] @calls))))

  (testing "accepted replay plus closed is success"
    (let [calls (atom [])
          steps (assoc (successful-steps calls true)
                       :resolve-replay
                       (recording-step
                        calls :replay
                        (fn [_]
                          (pipeline/outcome :success {:replayed? true})))
                       :apply-business-rules
                       (recording-step
                        calls :business
                        (fn [_]
                          (pipeline/outcome :rejected {:code :cart-closed}))))]
      (is (= :success (:outcome (pipeline/evaluate steps {}))))
      (is (= [:validate :replay] @calls))))

  (testing "stale plus closed is conflict"
    (let [calls (atom [])
          steps (assoc (successful-steps calls true)
                       :check-observation
                       (recording-step
                        calls :observation
                        (fn [_]
                          (pipeline/outcome :conflict {:code :cart-changed})))
                       :apply-business-rules
                       (recording-step
                        calls :business
                        (fn [_]
                          (pipeline/outcome :rejected {:code :cart-closed}))))]
      (is (= :conflict (:outcome (pipeline/evaluate steps {}))))
      (is (= [:validate :replay :observation] @calls)))))

(deftest operational-step-failures-are-not-converted-to-business-outcomes
  (let [steps (assoc (successful-steps (atom []) true)
                     :resolve-replay
                     (fn [_]
                       (throw (ex-info "idempotency store unavailable"
                                       {:type :operational-failure}))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"idempotency store unavailable"
                          (pipeline/evaluate steps {})))))
