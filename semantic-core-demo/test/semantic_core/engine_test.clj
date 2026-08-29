(ns semantic-core.engine-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [semantic-core.demo :as demo]
            [semantic-core.engine :as engine]
            [semantic-core.operators :refer [operators]]))

(def bundle-names ["ticket" "land" "corporate"])

(defn bundle-for [bundle-name]
  (demo/load-bundle bundle-name))

(defn result-for [bundle]
  (demo/execute (first (filter #(= bundle (:bundle-name %)) demo/cases))))

(deftest semantic-expression-language
  (let [env {:state {:count 2 :owner "state-owner"}
             :input {:data {:count 3 :owner "input-owner"}}}]
    (testing "literal, state, input, and nested vector expressions"
      (is (= 42 (engine/evaluate operators env 42)))
      (is (= :literal (engine/evaluate operators env [:value :literal])))
      (is (= 2 (engine/evaluate operators env [:state [:count]])))
      (is (= "input-owner"
             (engine/evaluate operators env [:input [:data :owner]])))
      (is (= [2 3]
             (engine/evaluate operators env
                              [[:state [:count]] [:input [:data :count]]]))))
    (testing "every core operator"
      (is (true? (engine/evaluate operators env
                                  [:op :core/= [:state [:count]] [:value 2]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/not= [:state [:owner]]
                                   [:input [:data :owner]]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/and [:value true] [:value true]])))
      (is (false? (engine/evaluate operators env
                                   [:op :core/and [:value true] [:value false]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/or [:value false] [:value true]])))
      (is (false? (engine/evaluate operators env
                                   [:op :core/or [:value false] [:value false]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/not [:value false]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/< [:value 1] [:value 2]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/<= [:value 2] [:value 2]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/> [:value 2] [:value 1]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/>= [:value 2] [:value 2]])))
      (is (true? (engine/evaluate operators env
                                  [:op :core/contains? [:value #{:known}]
                                   [:value :known]]))))
    (testing "unknown operators are rejected explicitly"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown operator"
                            (engine/evaluate operators env
                                             [:op :core/missing]))))))

(deftest recursive-template-rendering
  (let [env {:state {:owner "S"}
             :input {:data {:number 7}}}
        template {:scalar [:input [:data :number]]
                  :vector [[:state [:owner]] [:value :fixed]]
                  :set #{[:value :rendered]}
                  :literal "unchanged"}]
    (is (= {:scalar 7
            :vector ["S" :fixed]
            :set #{:rendered}
            :literal "unchanged"}
           (engine/render operators env template)))))

(deftest state-update-algebra
  (let [input {:data {:value 2}}
        set-state (engine/apply-update operators input {}
                                       [:set [:nested :value]
                                        [:input [:data :value]]])
        conjoined (engine/apply-update operators input set-state
                                       [:conj [:items] [:value :item]])
        disjoined (engine/apply-update operators input
                                       (assoc conjoined :flags #{:keep :remove})
                                       [:disj [:flags] [:value :remove]])]
    (is (= {:nested {:value 2}} set-state))
    (is (= [:item] (:items conjoined)))
    (is (= #{:keep} (:flags disjoined)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown state update"
                          (engine/apply-update operators input {}
                                               [:missing [:path] [:value 1]])))))

(deftest bundle-references-and-fsm-transitions
  (doseq [bundle-name bundle-names
          :let [bundle (bundle-for bundle-name)
                rules (set (map :rule/id (:rules bundle)))
                fsms-by-id (into {} (map (juxt :fsm/id identity) (:fsms bundle)))]]
    (testing (str bundle-name " bundle references definitions that exist")
      (is (= :core (get-in bundle [:operator-set :id])))
      (is (every? rules (mapcat :rules (:decisions bundle))))
      (is (every? fsms-by-id (vals (get-in bundle [:state-model :fsm-paths])))))
    (doseq [[path fsm-id] (get-in bundle [:state-model :fsm-paths])
            :let [fsm (fsms-by-id fsm-id)]]
      (is (= (:initial fsm) (get-in bundle (into [:state-model :initial] path))))
      (doseq [{:keys [from on to]} (:transitions fsm)]
        (testing (str bundle-name " transition " from " --" on "--> " to)
          (let [state (assoc-in (get-in bundle [:state-model :initial]) path from)]
            (is (= to
                   (get-in (engine/evolve operators bundle state {:event/type on})
                           path)))))))))

(deftest unknown-events-leave-aggregate-state-unchanged
  (doseq [bundle-name bundle-names
          :let [bundle (bundle-for bundle-name)
                state (get-in bundle [:state-model :initial])]]
    (is (= state
           (engine/evolve operators bundle state {:event/type :unknown/event})))))

(deftest same-engine-runs-ticket-bundle
  (let [r (result-for "ticket")]
    (is (= :accepted (get-in r [:decision :status])))
    (is (= :sold (get-in r [:after :seat/status])))
    (is (= :ticket/confirm-sale
           (get-in r [:reaction :commands 0 :command/type])))))

(deftest same-engine-runs-land-bundle
  (let [r (result-for "land")]
    (is (= :accepted (get-in r [:decision :status])))
    (is (= "P-2" (get-in r [:after :title/proprietor])))
    (is (= :land/register-transfer
           (get-in r [:reaction :commands 0 :command/type])))))

(deftest same-engine-runs-corporate-bundle
  (let [r (result-for "corporate")]
    (is (= :accepted (get-in r [:decision :status])))
    (is (= :approved (get-in r [:after :application/status])))
    (is (= :company/create
           (get-in r [:reaction :commands 0 :command/type])))))

(deftest corporate-four-eyes-rule-rejects
  (let [case (-> (first (filter #(= "corporate" (:bundle-name %)) demo/cases))
                 (assoc-in [:command :data :examiner-id] "E-1"))
        r (demo/execute case)]
    (is (= :rejected (get-in r [:decision :status])))
    (is (= :four-eyes-violation
           (get-in r [:decision :reason :code])))))

(deftest each-domain-rule-can-reject
  (let [rejection-cases
        [{:bundle "ticket"
          :events []
          :command {:command/type :ticket/confirm-sale
                    :data {:seat "A-10" :customer "C-1"}}
          :reason :seat-not-held}
         {:bundle "ticket"
          :events [{:event/type :ticket/seat-held
                    :data {:seat "A-10" :customer "C-2"}}]
          :command {:command/type :ticket/confirm-sale
                    :data {:seat "A-10" :customer "C-1"}}
          :reason :hold-owned-by-someone-else}
         {:bundle "land"
          :events []
          :command {:command/type :land/register-transfer
                    :data {:title-id "T-1" :from "P-1" :to "P-2"}}
          :reason :title-not-active}
         {:bundle "land"
          :events [{:event/type :land/title-created
                    :data {:title-id "T-1" :proprietor "P-2"}}]
          :command {:command/type :land/register-transfer
                    :data {:title-id "T-1" :from "P-1" :to "P-3"}}
          :reason :transferor-not-proprietor}
         {:bundle "corporate"
          :events []
          :command {:command/type :company/approve-application
                    :data {:application-id "A-1" :examiner-id "E-2"}}
          :reason :application-not-submitted}
         {:bundle "corporate"
          :events [{:event/type :company/application-submitted}]
          :command {:command/type :company/approve-application
                    :data {:application-id "A-1" :examiner-id "E-2"}}
          :reason :payment-not-confirmed}]]
    (doseq [{:keys [bundle events command reason]} rejection-cases]
      (testing (str bundle " rejects with " reason)
        (let [definition (bundle-for bundle)
              state (engine/hydrate operators definition events)
              result (engine/decide operators definition command state)]
          (is (= :rejected (:status result)))
          (is (empty? (:events result)))
          (is (= reason (get-in result [:reason :code]))))))))

(deftest unknown-command-has-no-decision-definition
  (let [bundle (bundle-for "ticket")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No decision definition"
                          (engine/decide operators bundle
                                         {:command/type :unknown/command}
                                         (get-in bundle [:state-model :initial]))))))

(deftest ticket-hold-expiration-clears-owner
  (let [bundle (bundle-for "ticket")
        held (engine/hydrate operators bundle
                             [{:event/type :ticket/seat-held
                               :data {:customer "C-1"}}])
        expired (engine/evolve operators bundle held
                               {:event/type :ticket/hold-expired})]
    (is (= :held (:seat/status held)))
    (is (= "C-1" (:seat/customer held)))
    (is (= :available (:seat/status expired)))
    (is (nil? (:seat/customer expired)))))

(deftest workflow-reactions-and-process-evolution
  (let [expected-status {"ticket" :payment-confirmed
                         "land" :registration-requested
                         "corporate" :company-requested}]
    (doseq [bundle-name bundle-names
            :let [bundle (bundle-for bundle-name)
                  case (first (filter #(= bundle-name (:bundle-name %)) demo/cases))
                  message (:workflow-message case)
                  initial (get-in bundle [:workflow :initial])
                  reaction (engine/react operators bundle message nil)
                  process-event (first (:events reaction))
                  evolved (engine/evolve-process operators bundle initial process-event)]]
      (testing (str bundle-name " uses initial process state and evolves its event")
        (is (= 1 (count (:events reaction))))
        (is (= 1 (count (:commands reaction))))
        (is (= (expected-status bundle-name) (:status evolved)))
        (is (= evolved
               (engine/evolve-process operators bundle evolved
                                      {:event/type :unknown/process-event}))))
      (testing (str bundle-name " ignores unrelated messages and false guards")
        (is (= {:events [] :commands [] :evidence nil}
               (engine/react operators bundle {:event/type :unknown/message} initial)))
        (is (= {:events [] :commands [] :evidence nil}
               (engine/react operators bundle message {:status :already-handled})))))))

(deftest workflow-reaction-condition-is-optional
  (let [bundle (update-in (bundle-for "ticket") [:workflow :reactions 0]
                          dissoc :when)
        message {:event/type :payment/confirmed
                 :data {:seat "A-10" :customer "C-1"}}
        reaction (engine/react operators bundle message {:status :any-state})]
    (is (= :payment-confirmed (get-in reaction [:evidence :reaction/id])))
    (is (= :ticket/confirm-sale
           (get-in reaction [:commands 0 :command/type])))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'semantic-core.engine-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
