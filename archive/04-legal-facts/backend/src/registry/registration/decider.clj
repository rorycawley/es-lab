(ns registry.registration.decider
  (:require [clojure.string  :as str]
            [registry.uuid   :as uuid]))

;; =============================================================================
;; Registration Application Decider
;;
;; Sources:
;;   The Companies Registration Act (CRA)
;;   Application State Machine (T1-T7)
;;   Business Rules catalogue (BR-002 to BR-010)
;;   Ubiquitous Language
;; =============================================================================

(def ^:private allowed-commands
  {:new               #{:create-registration-application}
   :draft             #{:submit-registration-application}
   :submitted         #{:begin-examination
                        :withdraw-registration-application}
   :under-examination #{:withdraw-registration-application
                        :approve-registration-application
                        :reject-registration-application}
   :registered        #{}
   :rejected          #{}
   :withdrawn         #{}})

(defn- command-allowed? [status command-type]
  (contains? (get allowed-commands status #{}) command-type))

(def terminal-states #{:registered :rejected :withdrawn})

(defn terminal? [state]
  (contains? terminal-states (:status state)))

(def initial-state {:status :new})

;; --- evolve ---

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :registration-application-created [_state event]
  {:status         :draft
   :application-id (:application/id event)
   :applicant-id   (:applicant/id event)})

(defmethod evolve :registration-application-submitted [state event]
  (assoc state
         :status                    :submitted
         :company-name              (:company/name event)
         :proposed-directors        (:proposed-directors event)
         :registered-office-address (:registered-office-address event)))

(defmethod evolve :examination-started [state event]
  (assoc state :status :under-examination :examiner-id (:examiner/id event)))

(defmethod evolve :registration-application-approved [state event]
  (assoc state :registrar-id (:registrar/id event)))

(defmethod evolve :company-registered [state event]
  ;; CRA s5 + s8 - company legally exists in the Register from this moment
  (assoc state
         :status              :registered
         :company-id          (:company/id event)
         :registration-number (:registration/number event)))

(defmethod evolve :registration-application-rejected [state event]
  (assoc state
         :status           :rejected
         :registrar-id     (:registrar/id event)
         :rejection-reason (:rejection/reason event)))

(defmethod evolve :registration-application-withdrawn [state _event]
  (assoc state :status :withdrawn))

(defmethod evolve :default [state _event] state)

(defn fold [events]
  (reduce evolve initial-state events))

;; --- tracing ---

(defn- base-event [command event-type]
  {:event/id             (uuid/v7)
   :event/type           event-type
   :event/causation-id   (:command/id command)
   :event/correlation-id (:command/correlation-id command)
   :occurred-at          (java.time.Instant/now)})

;; --- business rule predicates ---

(defn- name-stated? [cmd]
  (not (str/blank? (:company-name cmd))))

(defn- at-least-two-proposed-directors? [cmd]
  (>= (count (:proposed-directors cmd)) 2))

(defn- address-stated? [cmd]
  (some? (:registered-office-address cmd)))

(defn- all-natural-persons? [cmd]
  (every? :natural-person? (:proposed-directors cmd)))

(defn- all-identity-verified? [cmd]
  (every? :identity-verified? (:proposed-directors cmd)))

(defn- rejection-reason-stated? [cmd]
  (not (str/blank? (:rejection-reason cmd))))

;; --- command handlers ---

(defmulti ^:private decide-command
  (fn [_state command] (:command/type command)))

(defmethod decide-command :create-registration-application [_state cmd]
  {:events [(merge (base-event cmd :registration-application-created)
                   {:application/id (uuid/v7)
                    :applicant/id   (:applicant-id cmd)})]})

(defmethod decide-command :submit-registration-application [state cmd]
  (cond
    (not (name-stated? cmd))                    {:error :company-name-required}
    (not (at-least-two-proposed-directors? cmd)) {:error :at-least-two-proposed-directors-required}
    (not (address-stated? cmd))                 {:error :registered-office-address-required}
    (not (all-natural-persons? cmd))            {:error :all-proposed-directors-must-be-natural-persons}
    (not (all-identity-verified? cmd))          {:error :all-proposed-directors-must-be-identity-verified}
    :else
    {:events [(merge (base-event cmd :registration-application-submitted)
                     {:application/id            (:application-id state)
                      :applicant/id              (:applicant-id state)
                      :company/name              (:company-name cmd)
                      :proposed-directors        (:proposed-directors cmd)
                      :registered-office-address (:registered-office-address cmd)})]}))

(defmethod decide-command :begin-examination [state cmd]
  {:events [(merge (base-event cmd :examination-started)
                   {:application/id (:application-id state)
                    :examiner/id    (:examiner-id cmd)})]})

;; T6 - emits two events atomically per CRA s5
;; Business rules BR-002 through BR-006 are checked against the submitted state,
;; not the approval command body. The registrar re-verifies what was submitted;
;; the command only carries registrar-id and the injected address check function.
;; BR-008 is a cross-aggregate invariant enforced by the event store transaction.
(defmethod decide-command :approve-registration-application [state cmd]
  (let [company-name  (:company-name state)
        directors     (:proposed-directors state)
        address       (:registered-office-address state)
        addr-valid?   (get cmd :address-valid-fn (constantly true))]
    (cond
      (str/blank? company-name)                            {:error :company-name-required}
      (< (count directors) 2)                              {:error :at-least-two-proposed-directors-required}
      (nil? address)                                       {:error :registered-office-address-required}
      (not (every? :natural-person? directors))            {:error :all-proposed-directors-must-be-natural-persons}
      (not (every? :identity-verified? directors))         {:error :all-proposed-directors-must-be-identity-verified}
      (not (addr-valid? address))                          {:error :registered-office-address-not-valid}
      :else
      (let [company-id (uuid/v7)]
        {:events [(merge (base-event cmd :registration-application-approved)
                         {:application/id (:application-id state)
                          :registrar/id   (:registrar-id cmd)})
                  (merge (base-event cmd :company-registered)
                         {:application/id            (:application-id state)
                          :company/id                company-id
                          :company/name              company-name
                          :registered-office-address address
                          :registration/number       (str "IE-" (java.time.Year/now) "-" company-id)})]}))))

(defmethod decide-command :reject-registration-application [state cmd]
  (if (not (rejection-reason-stated? cmd))
    {:error :rejection-reason-required}
    {:events [(merge (base-event cmd :registration-application-rejected)
                     {:application/id   (:application-id state)
                      :registrar/id     (:registrar-id cmd)
                      :rejection/reason (:rejection-reason cmd)})]}))

(defmethod decide-command :withdraw-registration-application [state cmd]
  {:events [(merge (base-event cmd :registration-application-withdrawn)
                   {:application/id (:application-id state)
                    :applicant/id   (:applicant-id state)})]})

(defmethod decide-command :default [_state cmd]
  {:error :unknown-command :command-type (:command/type cmd)})

(defn decide [state command]
  (let [status       (:status state)
        command-type (:command/type command)]
    (if-not (command-allowed? status command-type)
      {:error :command-not-allowed-in-current-state :state status :command command-type}
      (decide-command state command))))

(def registration-decider
  {:initial-state initial-state
   :decide        decide
   :evolve        evolve
   :fold          fold
   :terminal?     terminal?})
