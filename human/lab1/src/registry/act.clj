(ns registry.act
  "What makes something an act rather than a record of a data change.

   The output of this lab is two functions — `act?` and `unanswered`. The
   values below are examples of what those two say about six candidates; they
   are not themselves the point.

   The case is Case 1 from ../../README_EXAMPLES.md: a surname mistyped on
   registration in 2019, found and rectified seven years later. Nothing about
   the ownership of the land ever changed. Only the record was wrong.")

;; ---------------------------------------------------------------------------
;; The filter — Q6
;;
;; "Was a statutory power exercised, and would the register be different if
;; this had not happened?" Both must be yes.
;; ---------------------------------------------------------------------------

(defn act?
  "Was a statutory power exercised?

   Deliberately the weakest possible check: something was named under
   :act/power. It cannot tell you the provision is the right one — only that
   somebody was made to name one. That is most of the value: `NotificationSent`
   and `PaymentReceived` cannot answer this question at all, and the filter is
   what keeps them out of the record that has to be readable as evidence."
  [candidate]
  (some? (:act/power candidate)))

;; ---------------------------------------------------------------------------
;; The thirteen questions — Q1 to Q13
;;
;; Not a schema. These are the questions a lawyer would ask in a witness box,
;; and the schema is derived from them rather than designed. A validation
;; library would give us the constraint and silently discard the reason for
;; it — and the key someone eventually deletes as unread will be Q4, which is
;; the answer to whether *this officer* was entitled to exercise the power.
;; ---------------------------------------------------------------------------

(def questions
  "Q1–Q13 mapped to the act key that answers each."
  {:q1  :act/type          ; What was done?
   :q2  :act/subjects      ; To what was it done?
   :q3  :act/actor         ; Who did it?
   :q4  :act/delegation    ; Under what delegation?
   :q5  :act/instance      ; At whose instance?
   :q6  :act/power         ; Under what power?
   :q7  :act/regime        ; Under what regime?
   :q8  :act/basis         ; On what evidence?
   :q9  :act/effective-at  ; When did it take effect?
   :q10 :act/recorded-at   ; When was it recorded?
   :q11 :act/priority      ; What legal priority applied?
   :q12 :act/decision      ; What was decided?
   :q13 :act/effect})      ; What did it change?

(defn unanswered
  "The questions this act leaves unresolved.

   A key holding :not-applicable is answered — a regime may genuinely not
   apply, and a corporate registry has no priority rules at all. A key that is
   absent, or present holding nil, is not: that is 'unknown because we failed
   to capture it', which is never a valid answer.

   Hence `some?` rather than `contains?`. The distinction is the whole of it:
   one says a question was considered and did not arise, the other says nobody
   looked."
  [act]
  (into (sorted-set)
        (remove #(some? (get act (questions %))))
        (keys questions)))

;; ===========================================================================
;; 1 — What CRUD would store
;;
;; True, and not evidence. It records that Máire owns DN12345 and nothing
;; about how the registry came to say so, under what power, or when.
;; ===========================================================================

(def folio-row
  {:folio          "DN12345"
   :proprietor     "Máire Ní Bhriain"
   :class-of-title "absolute"})

;; ===========================================================================
;; 2 — The too-fine failure
;;
;; A field change. There is no statutory power to change a proprietor's name
;; in isolation, so this cannot answer Q6. The act was a registration of
;; transfer, and decomposing it evaporates the legal meaning.
;; ===========================================================================

(def field-change
  {:changed :proprietor/name
   :from    "Maire Ni Bhrian"
   :to      "Máire Ní Bhriain"})

;; ===========================================================================
;; 3 — The act
;;
;; 14 March 2019. The registering officer enters the surname as "Ni Bhrian".
;; Every key is annotated with the question it answers.
;; ===========================================================================

(def registration-of-transfer
  {:act/type         :registration-of-transfer                          ; Q1
   :act/subjects     [{:folio "DN12345"}]                               ; Q2
   :act/actor        {:id "REG-0042"}                                   ; Q3
   :act/delegation   "DEL-2018-004"                                     ; Q4
   :act/instance     :party-lodged                                      ; Q5
   :act/power        {:instrument "Registration of Title Act 1964"
                      :provision  "s.[x]"}                              ; Q6
   :act/regime       "ie-land-2019.01.14"                               ; Q7
   :act/basis        [{:type   :instrument
                       :kind   :transfer
                       :doc/id "DOC-99187"
                       :doc/hash "sha256:9c1f00000000000000000000000000"}] ; Q8
   :act/effective-at #inst "2019-03-14T11:42:00.000-00:00"              ; Q9
   :act/recorded-at  #inst "2019-03-14T11:42:03.000-00:00"              ; Q10
   :act/priority     {:from-lodgement "2019/004120"
                      :lodged-at #inst "2019-02-27T09:15:00.000-00:00"} ; Q11
   :act/decision     {:outcome        :registered
                      :class-of-title :absolute}                        ; Q12
   :act/effect       {:proprietor/from [{:party/id "P-3341"}]
                      :proprietor/to   [{:party/id  "P-8802"
                                         :filed-as  "Máire Ní Bhriain"}]}}) ; Q13

;; The load-bearing detail is `:filed-as`. The transfer deed said
;; "Máire Ní Bhriain"; the register said "Maire Ni Bhrian". The discrepancy is
;; visible in the record from the moment it is made — which is how the error is
;; found seven years later, and how the ground for rectification is established.

;; ===========================================================================
;; 4 — Three things that are not acts
;;
;; One per store. Each is missing :act/power, which is the whole of the test.
;; Note the key namespaces: nothing here pretends to be an act.
;; ===========================================================================

(def notification-sent
  "Operational. The legal consequence already occurred; telling the solicitor
   about it exercised no power and changed nothing."
  {:op/type  :registration-notification-sent
   :op/folio "DN12345"
   :op/to    "SOL-1188"
   :op/at    #inst "2019-03-14T11:45:00.000-00:00"})

(def official-search
  "Assertion ledger. The registry reported; it decided nothing. Neither an act
   nor operational — and the one artefact a two-store model loses, because it
   is the only proof of what the purchaser was actually shown (C10).

   Where a search confers priority protection it is *also* an act. This one
   does not."
  {:assertion/type      :official-search
   :assertion/subject   {:folio "DN12345"}
   :assertion/cutoffs   {:effect-time #inst "2022-08-02T14:31:00.000-00:00"
                         :record-time #inst "2022-08-02T14:31:00.000-00:00"}
   :assertion/issued-at #inst "2022-08-02T14:31:06.000-00:00"
   :assertion/requester {:id "SOL-2277" :entitlement :public-search}
   :assertion/content   {:proprietor     "Maire Ni Bhrian"
                         :class-of-title :absolute}
   :assertion/hash      "sha256:41ba00000000000000000000000000"})

(def regime-deployed
  "Audit. Authority over the *system*, not over the register — even though the
   rectification below depends on it, and even though it feels consequential."
  {:audit/type   :rule-regime-deployed
   :audit/regime "ie-land-2026.07.01"
   :audit/actor  {:id "OPS-0007"}
   :audit/at     #inst "2026-07-01T00:04:00.000-00:00"})

(def not-acts
  "Three candidates, one per store, none of them an act."
  {:operational      notification-sent
   :assertion-ledger official-search
   :audit            regime-deployed})

;; ===========================================================================
;; 5 — The act is not the envelope
;;
;; Stream positions, schema versions and correlation ids are all necessary.
;; None of them is part of the act. Merge the two and the discipline is gone
;; within a release: a lawyer in a witness box does not care about stream
;; position 2, and once machinery sits among the thirteen answers nobody can
;; tell which keys are which.
;;
;;   The envelope exists to make the software reliable.
;;   The act exists to make the institution intelligible.
;; ===========================================================================

(def registration-in-a-stream
  {:envelope {:stream/id      "DN12345"
              :stream/version 2
              :schema/version 1
              :correlation/id #uuid "0f1c2b3a-0000-4000-8000-000000000001"}
   :act      registration-of-transfer})

;; ===========================================================================
;; 6 — The rectification
;;
;; 19 August 2026. The error is found on a subsequent dealing and corrected
;; with effect from 14 March 2019.
;;
;; Four differences from act 3 carry the whole argument: a different act type,
;; a different statutory power, an effect time seven years before the record
;; time, and an explicit link to the act being corrected.
;; ===========================================================================

(def rectification-of-register
  {:act/type         :rectification-of-register                ; Q1  — different act type
   :act/subjects     [{:folio "DN12345"}]                      ; Q2
   :act/actor        {:id "REG-0011"}                          ; Q3
   :act/delegation   "DEL-2025-019"                            ; Q4  — senior delegation
   :act/instance     :registrar-own-motion                     ; Q5  — nobody filed
   :act/power        {:instrument "Registration of Title Act 1964"
                      :provision  "s.[y]"}                     ; Q6  — different power
   :act/regime       "ie-land-2026.07.01"                      ; Q7  — today's regime
   :act/basis        [{:type :instrument
                       :doc/id "DOC-99187"
                       :doc/hash "sha256:9c1f00000000000000000000000000"}
                      {:type :internal-report :ref "ERR-2026-0881"}] ; Q8
   :act/corrects     {:act/type :registration-of-transfer
                      :act/recorded-at #inst "2019-03-14T11:42:03.000-00:00"}
   :act/effective-at #inst "2019-03-14T11:42:00.000-00:00"     ; Q9  — BACKDATED
   :act/recorded-at  #inst "2026-08-19T10:07:00.000-00:00"     ; Q10 — today
   :act/priority     :not-applicable                           ; Q11 — fixes no priority
   :act/decision     {:outcome :rectified
                      :ground  :clerical-error                 ; Q12 — enumerated, never prose
                      :consent-of-affected-parties true}
   :act/effect       {:corrected-field  :proprietor/name
                      :as-recorded      "Maire Ni Bhrian"
                      :should-have-read "Máire Ní Bhriain"}})  ; Q13

;; Had the ground been :fraud, the consequences and the appeal rights would
;; differ — which is why grounds are enumerated values drawn from the regime
;; and never free text.

;; ---------------------------------------------------------------------------
;; The act record for DN12345. Everything kept forever, and only that.
;;
;; Lab 2 folds this with `evolve` and gets the register back. Note that it is
;; two records out of the seventeen this case produced.
;; ---------------------------------------------------------------------------

(def acts
  [registration-of-transfer
   rectification-of-register])
