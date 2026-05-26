(ns registry.registration.decider-test
  (:require [clojure.test :refer [deftest is testing]]
            [registry.registration.decider :as sut]))

(def verified-director-1
  {:id "dir-001" :name "Jane Smith"  :natural-person? true :identity-verified? true})
(def verified-director-2
  {:id "dir-002" :name "Alice Jones" :natural-person? true :identity-verified? true})
(def unverified-director
  {:id "dir-003" :name "John Doe"    :natural-person? true :identity-verified? false})
(def non-natural-person
  {:id "dir-004" :name "Acme Holdings" :natural-person? false :identity-verified? true})
(def valid-office
  {:address-line-1 "1 Main Street" :city "Dublin" :country "IE"})

(defn cmd [type extras]
  (merge {:command/type type
          :command/id             (java.util.UUID/randomUUID)
          :command/correlation-id (java.util.UUID/randomUUID)}
         extras))

(defn run-commands [commands]
  (reduce
   (fn [events command]
     (let [state  (sut/fold events)
           result (sut/decide state command)]
       (if (:error result)
         (reduced result)
         (into events (:events result)))))
   [] commands))

(defn state-after [commands]
  (let [r (run-commands commands)]
    (if (:error r) r (sut/fold r))))

(defn draft-events []
  (run-commands [(cmd :create-registration-application {:applicant-id "a-1"})]))

(defn submitted-events []
  (run-commands [(cmd :create-registration-application {:applicant-id "a-1"})
                 (cmd :submit-registration-application
                      {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                       :registered-office-address valid-office})]))

(defn under-examination-events []
  (run-commands [(cmd :create-registration-application {:applicant-id "a-1"})
                 (cmd :submit-registration-application
                      {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                       :registered-office-address valid-office})
                 (cmd :begin-examination {:examiner-id "e-1"})]))

;; terminal?
(deftest terminal-state-tests
  (is (sut/terminal? {:status :registered}))
  (is (sut/terminal? {:status :rejected}))
  (is (sut/terminal? {:status :withdrawn}))
  (is (not (sut/terminal? {:status :draft})))
  (is (not (sut/terminal? {:status :submitted})))
  (is (not (sut/terminal? {:status :under-examination}))))

;; state machine
(deftest state-machine-tests
  (testing "cannot submit from :new"
    (is (= :command-not-allowed-in-current-state
           (:error (sut/decide sut/initial-state (cmd :submit-registration-application {}))))))
  (testing "cannot approve from :submitted"
    (let [state (sut/fold (submitted-events))]
      (is (= :command-not-allowed-in-current-state
             (:error (sut/decide state (cmd :approve-registration-application {})))))))
  (testing "terminal states reject all commands"
    (doseq [status [:registered :rejected :withdrawn]
            cmd-type [:submit-registration-application :approve-registration-application]]
      (is (= :command-not-allowed-in-current-state
             (:error (sut/decide {:status status} (cmd cmd-type {}))))))))

;; BR-002
(deftest br-002-company-name-required
  (let [state (sut/fold (draft-events))]
    (is (= :company-name-required
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "" :proposed-directors [verified-director-1 verified-director-2]
                                           :registered-office-address valid-office})))))))

;; BR-003 — TWO directors required
(deftest br-003-at-least-two-directors
  (let [state (sut/fold (draft-events))]
    (is (= :at-least-two-proposed-directors-required
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "Acme Ltd" :proposed-directors [verified-director-1]
                                           :registered-office-address valid-office})))))
    (is (= :at-least-two-proposed-directors-required
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "Acme Ltd" :proposed-directors []
                                           :registered-office-address valid-office})))))))

;; BR-004
(deftest br-004-address-required
  (let [state (sut/fold (draft-events))]
    (is (= :registered-office-address-required
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                                           :registered-office-address nil})))))))

;; BR-005
(deftest br-005-natural-persons
  (let [state (sut/fold (draft-events))]
    (is (= :all-proposed-directors-must-be-natural-persons
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "Acme Ltd"
                                           :proposed-directors [verified-director-1 non-natural-person]
                                           :registered-office-address valid-office})))))))

;; BR-006
(deftest br-006-identity-verified
  (let [state (sut/fold (draft-events))]
    (is (= :all-proposed-directors-must-be-identity-verified
           (:error (sut/decide state (cmd :submit-registration-application
                                          {:company-name "Acme Ltd"
                                           :proposed-directors [verified-director-1 unverified-director]
                                           :registered-office-address valid-office})))))))

;; BR-007
(deftest br-007-rejection-reason
  (let [state (sut/fold (under-examination-events))]
    (is (= :rejection-reason-required
           (:error (sut/decide state (cmd :reject-registration-application
                                          {:registrar-id "r-1" :rejection-reason ""})))))))

;; Approval command shape — only carries registrar-id and injected check functions.
;; All submitted data (company-name, directors, address) comes from state.
(defn- approve-cmd
  ([]    (approve-cmd (constantly true)))
  ([addr-valid?]
   (cmd :approve-registration-application
        {:registrar-id     "r-1"
         :address-valid-fn addr-valid?})))

;; Hypothetical under-examination state — used to test the defensive re-check of
;; BR-002 through BR-006 at approval (CRA §5). These states cannot be reached via
;; normal submission (which enforces the same rules) but could arise if a director's
;; verified status changes between submission and approval.
(defn- examination-state [overrides]
  (merge {:status                    :under-examination
          :application-id            "app-001"
          :company-name              "Acme Ltd"
          :proposed-directors        [verified-director-1 verified-director-2]
          :registered-office-address valid-office}
         overrides))

;; BR-002 through BR-006 re-checked at T6 — CRA §5 requires full compliance at approval
(deftest br-002-company-name-required-at-approval
  (is (= :company-name-required
         (:error (sut/decide (examination-state {:company-name ""}) (approve-cmd))))))

(deftest br-003-at-least-two-directors-at-approval
  (is (= :at-least-two-proposed-directors-required
         (:error (sut/decide (examination-state {:proposed-directors [verified-director-1]}) (approve-cmd))))))

(deftest br-004-address-required-at-approval
  (is (= :registered-office-address-required
         (:error (sut/decide (examination-state {:registered-office-address nil}) (approve-cmd))))))

(deftest br-005-natural-persons-at-approval
  (is (= :all-proposed-directors-must-be-natural-persons
         (:error (sut/decide (examination-state {:proposed-directors [verified-director-1 non-natural-person]}) (approve-cmd))))))

(deftest br-006-identity-verified-at-approval
  (is (= :all-proposed-directors-must-be-identity-verified
         (:error (sut/decide (examination-state {:proposed-directors [verified-director-1 unverified-director]}) (approve-cmd))))))

;; BR-010 — addr-valid? is injected into the cmd; checked against state's address
(deftest br-010-address-valid
  (let [state (sut/fold (under-examination-events))]
    (is (= :registered-office-address-not-valid
           (:error (sut/decide state (approve-cmd (constantly false))))))))

;; T7 — rejection happy path
(deftest rejection-happy-path
  (let [final (state-after
               [(cmd :create-registration-application {:applicant-id "a-1"})
                (cmd :submit-registration-application
                     {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                      :registered-office-address valid-office})
                (cmd :begin-examination {:examiner-id "e-1"})
                (cmd :reject-registration-application
                     {:registrar-id "r-1" :rejection-reason "Name is deceptive under §8"})])]
    (is (= :rejected (:status final)))
    (is (sut/terminal? final))
    (is (= "Name is deceptive under §8" (:rejection-reason final)))))

;; Two events emitted atomically at approval — CRA s5
(deftest approval-emits-two-events
  (let [state  (sut/fold (under-examination-events))
        result (sut/decide state (approve-cmd))]
    (is (= 2 (count (:events result))))
    (is (= :registration-application-approved (-> result :events first :event/type)))
    (is (= :company-registered                (-> result :events second :event/type)))
    (is (some? (-> result :events second :registration/number)))))

;; company-registered carries all legally mandated fields
(deftest company-registered-event-has-required-fields
  (let [state  (sut/fold (under-examination-events))
        ev     (-> (sut/decide state (approve-cmd)) :events second)]
    (is (= :company-registered (:event/type ev)))
    (is (some? (:application/id ev)))
    (is (= "Acme Ltd" (:company/name ev)))
    (is (some? (:registration/number ev)))
    (is (some? (:occurred-at ev)))))

;; Full lifecycle
(deftest full-lifecycle-reaches-registered
  (let [final (state-after
               [(cmd :create-registration-application {:applicant-id "a-1"})
                (cmd :submit-registration-application
                     {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                      :registered-office-address valid-office})
                (cmd :begin-examination {:examiner-id "e-1"})
                (cmd :approve-registration-application
                     {:registrar-id     "r-1"
                      :address-valid-fn (constantly true)})])]
    (is (= :registered (:status final)))
    (is (sut/terminal? final))
    (is (some? (:registration-number final)))))

;; Withdrawal paths T4 and T5
(deftest withdrawal-tests
  (testing "T4 - withdraw from submitted"
    (let [final (state-after
                 [(cmd :create-registration-application {:applicant-id "a-1"})
                  (cmd :submit-registration-application
                       {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                        :registered-office-address valid-office})
                  (cmd :withdraw-registration-application {:applicant-id "a-1"})])]
      (is (= :withdrawn (:status final)))
      (is (sut/terminal? final))))
  (testing "T5 - withdraw from under examination"
    (let [final (state-after
                 [(cmd :create-registration-application {:applicant-id "a-1"})
                  (cmd :submit-registration-application
                       {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                        :registered-office-address valid-office})
                  (cmd :begin-examination {:examiner-id "e-1"})
                  (cmd :withdraw-registration-application {:applicant-id "a-1"})])]
      (is (= :withdrawn (:status final)))
      (is (sut/terminal? final)))))

;; Audit evidence
(deftest audit-evidence-tests
  (testing "every event has occurred-at"
    (let [events (run-commands
                  [(cmd :create-registration-application {:applicant-id "a-1"})
                   (cmd :submit-registration-application
                        {:company-name "Acme Ltd" :proposed-directors [verified-director-1 verified-director-2]
                         :registered-office-address valid-office})])]
      (is (every? :occurred-at events))))
  (testing "causation-id matches command/id"
    (let [my-cmd (cmd :create-registration-application {:applicant-id "a-1"})
          result (sut/decide sut/initial-state my-cmd)]
      (is (= (:command/id my-cmd) (:event/causation-id (first (:events result))))))))
