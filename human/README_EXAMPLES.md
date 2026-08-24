# Registry System — Worked Examples

Companion to the Guiding Specification. The specification states the rules; this document shows them operating on a case. If neither is familiar, start with **The Essentials**.

**Why this document exists.** A specification can be internally consistent and still wrong about the domain. A worked example is the only form both a registrar and an engineer can check — one against the statute, one against the schema. If a case cannot be narrated end to end, the model does not yet handle it.

Names, folio numbers and provision references are illustrative. Replace them with real ones from a closed case before putting this in front of registry staff.

---

## Case 1 — A clerical error, discovered seven years later

The case that proves the model. If this works, most things work.

### The story

| Date | What happened |
|---|---|
| 27 Feb 2019 | The transfer is lodged. Priority is fixed from this moment. |
| 14 Mar 2019 | The transfer is registered. The registering officer enters the surname as **"Ni Bhrian"** — a clerical error. |
| 2 Aug 2022 | A purchaser's solicitor obtains an official search on DN12345. It reports the proprietor as **"Maire Ni Bhrian"**. |
| 19 Aug 2026 | The error is discovered on a subsequent dealing. A rectification is registered, correcting the name with effect from 14 March 2019. |

Nothing about the ownership of the land ever changed. Only the record was wrong — for seven years, and demonstrably so.

### Act 1 — the lodgement

Lodgement is a separate act because it does separate legal work: it fixes priority, weeks before title passes. Folding it into the registration would give one act with two effect times, which is the *too coarse* failure the specification warns about.

```clojure
{:act/type          :dealing-lodged
 :act/subjects      [{:folio "DN12345"}]
 :act/instance      :party-lodged
 :act/power         {:instrument "Registration of Title Act 1964"
                     :provision  "s.[w]"}
 :act/regime        "ie-land-2019.01.14"
 :act/effective-at  #inst "2019-02-27T09:15:00Z"   ; priority runs from here
 :act/recorded-at   #inst "2019-02-27T09:15:04Z"
 :act/priority      {:dealing "2019/004120"}
 :act/decision      {:outcome :lodged}
 :act/effect        {:dealing-pending "2019/004120"}}
```

### Act 2 — the registration

```clojure
{:act/type          :registration-of-transfer          ; Q1
 :act/subjects      [{:folio "DN12345"}]                ; Q2
 :act/actor         {:id "REG-0042"}                    ; Q3
 :act/delegation    "DEL-2018-004"                      ; Q4
 :act/instance      :party-lodged                       ; Q5
 :act/power         {:instrument "Registration of Title Act 1964"
                     :provision  "s.[x]"}               ; Q6
 :act/regime        "ie-land-2019.01.14"                ; Q7
 :act/basis         [{:type :instrument :kind :transfer
                      :doc/id "DOC-99187"
                      :doc/hash "sha256:9c1f…"}]        ; Q8
 :act/effective-at  #inst "2019-03-14T11:42:00Z"        ; Q9
 :act/recorded-at   #inst "2019-03-14T11:42:03Z"        ; Q10
 :act/priority      {:from-lodgement "2019/004120"
                     :lodged-at #inst "2019-02-27T09:15:00Z"}  ; Q11
 :act/decision      {:outcome :registered
                     :class-of-title :absolute}         ; Q12
 :act/effect        {:proprietor/from [{:party/id "P-3341"}]
                     :proprietor/to   [{:party/id "P-8802"
                                        :filed-as "Máire Ní Bhriain"}]}}  ; Q13
```

Note `:filed-as`. The transfer deed said *Máire Ní Bhriain*. The register said *Maire Ni Bhrian*. **The discrepancy is visible in the record from the moment it is made** — which is how the error is later found, and how the ground for rectification is established.

### The issued assertion — 2 August 2022

Not an act. The registry decided nothing; it reported. But it is preserved, because a purchaser relied on it.

```clojure
{:assertion/type    :official-search
 :assertion/subject {:folio "DN12345"}
 :assertion/cutoffs {:effect-time  #inst "2022-08-02T14:31:00Z"
                     :record-time  #inst "2022-08-02T14:31:00Z"}
 :assertion/issued-at #inst "2022-08-02T14:31:06Z"
 :assertion/requester {:id "SOL-2277" :entitlement :public-search}
 :assertion/content {:proprietor "Maire Ni Bhrian"
                     :class-of-title :absolute
                     :burdens [...]}
 :assertion/hash    "sha256:41ba…"}
```

> Where a search confers priority protection, it is **also** an act — it changes the legal position. A plain search, as here, is an assertion only.

### Act 3 — the rectification

```clojure
{:act/type          :rectification-of-register          ; Q1  — different act type
 :act/subjects      [{:folio "DN12345"}]
 :act/actor         {:id "REG-0011"}
 :act/delegation    "DEL-2025-019"                      ; Q4  — senior delegation
 :act/instance      :registrar-own-motion               ; Q5  — nobody filed
 :act/power         {:instrument "Registration of Title Act 1964"
                     :provision  "s.[y]"}               ; Q6  — different power
 :act/regime        "ie-land-2026.07.01"                ; Q7  — today's regime
 :act/basis         [{:type :instrument :doc/id "DOC-99187"
                      :doc/hash "sha256:9c1f…"}
                     {:type :internal-report :ref "ERR-2026-0881"}]
 :act/corrects      {:act/id "…act-2…"}
 :act/effective-at  #inst "2019-03-14T11:42:00Z"        ; Q9  — BACKDATED
 :act/recorded-at   #inst "2026-08-19T10:07:00Z"        ; Q10 — today
 :act/decision      {:outcome :rectified
                     :ground  :clerical-error           ; Q12 — enumerated, not free text
                     :consent-of-affected-parties true}
 :act/effect        {:corrected-field  :proprietor/name
                     :as-recorded      "Maire Ni Bhrian"
                     :should-have-read "Máire Ní Bhriain"}}
```

**Four differences from act 2 carry the whole argument:** a different act type, a different statutory power, an effect time seven years before the record time, and an explicit link to the act being corrected.

Note also `:ground :clerical-error`. Had the ground been `:fraud`, the consequences and the appeal rights would differ — which is why grounds are enumerated values drawn from the regime and never prose.

### What the system now answers

| The question | Effect-time cut-off | Record-time cut-off | Answer | Source |
|---|---|---|---|---|
| Who owns it today? | now | now | Máire Ní Bhriain | reconstruction |
| Who owned it on 1 Jan 2020? | 2020-01-01 | now | Máire Ní Bhriain | reconstruction |
| What did we assert on 1 Jan 2020? | 2020-01-01 | 2020-01-01 | Maire Ni Bhrian | reconstruction |
| What did the 2022 search actually say? | — | — | Maire Ni Bhrian | assertion ledger |
| Did the title change, or were we wrong? | — | — | We were wrong — `:rectification-of-register`, ground `:clerical-error` | act type |
| For how long was the register wrong? | — | — | 14 Mar 2019 to 19 Aug 2026 | the two record times |

**Rows three and four agree — and that is the point of keeping both.** Reconstruction says what the register *should* have reported; the ledger says what it *did* report. They agree when the software was correct. Had a projection defect been in play, they would diverge, and only row four is evidence of what the purchaser was shown. That is why C10 exists.

### Everything this case produced

The three acts above are not everything the system stored. They are a small fraction of it.

| Artefact | Store | Act? |
|---|---|---|
| Command `:lodge-dealing`, with idempotency key | Operational | |
| **Lodgement of dealing 2019/004120** | Act record | **Yes** |
| Fee payment received | Operational | |
| Transfer deed `DOC-99187` | Document archive | |
| Case assigned to officer `REG-0042` | Operational | |
| Command `:register-transfer` | Operational | |
| **Registration of transfer** | Act record | **Yes** |
| Notification of registration sent to the lodging solicitor | Operational | |
| Search request from `SOL-2277` | Operational | |
| Entitlement checked — public search, granted | Audit store | |
| Read of folio DN12345 by `SOL-2277` | Audit store | |
| **Official search issued** | Assertion ledger | |
| Regime `ie-land-2026.07.01` deployed to production | Audit store | |
| Internal error report `ERR-2026-0881` | Document archive | |
| Command `:rectify-register` | Operational | |
| **Rectification of register** | Act record | **Yes** |
| Notification to affected parties | Operational | |

**Seventeen records. Three acts.**

Three things worth noticing.

**The issued search is neither an act nor operational.** It is the one artefact that a two-store model loses, and it is the only proof of what the purchaser was actually shown.

**The regime deployment is audit, not an act.** It is authority over the system, not over the register — even though act 3 depends on it, and even though it feels consequential.

**Four retention regimes are in play.** The acts are permanent. The search is kept for the statutory period. The audit entries have a lawful *maximum* as well as a minimum (**U7**). The operational records should be pruned. One store cannot serve all four.

Had all seventeen been written to a single log, reconstruction would have to filter — and that filter would become a correctness dependency for every historical answer the registry gives.

### Which commitments this exercises

| Commitment | Where it shows |
|---|---|
| C1 | No row was overwritten; the register at any date is computed from these two acts |
| C2 | Rectification carries effect time 2019 and record time 2026 |
| C3 | Both acts name power, actor, delegation, instance and evidence — and the two differ on every one |
| C5 | Act 1 is bound to the 2019 regime, act 2 to the 2026 regime |
| C6 | `:class-of-title :absolute` was a judgement, stored, never recomputed |
| C8 | Rows two and three of the table above are different questions with different, permanent answers |
| C9 | Every row is a fold over acts; no legal rule is evaluated |
| C10 | Row four is answerable at all |

### What a CRUD system would produce

`UPDATE folio SET proprietor_name = 'Máire Ní Bhriain' WHERE folio_id = 'DN12345'`

A system-versioned table would faithfully record that a column changed on 19 August 2026. It would not record the statutory power, the ground, the delegation, the link to the act corrected, or the fact that the correction takes effect in 2019. And row four — what the purchaser was actually shown — was never captured at all.

**The information is not lost. It was never present in the statement.**

---

## Case 2 — Late notification and deemed cessation

The same mechanics in a corporate registry, compressed. Both cases turn on effect time diverging from record time, but for different reasons.

| | Late notification | Deemed cessation |
|---|---|---|
| What happens | A director resigns 30 July; the company files on 11 August | A director dies on 3 March; the registry learns on 20 March |
| Effect time | 30 July | 3 March |
| Record time | 11 August | 20 March |
| Q5 — at whose instance | `:company-filed` | `:registrar-own-motion` |
| Was the register wrong in between? | Yes, for twelve days | Yes, for seventeen days |
| Who was at fault | Nobody — the statute allows the filing period | Nobody — no filing was ever required |
| Kind of act | The registry **recognises** a fact | The registry **accepts** a fact arising by operation of law |

Neither is a registry act in the narrow sense of the registry causing the change. Both are acts in the sense C1 defines: the registry exercising authority over the record.

**Why the second case matters more than it looks.** For seventeen days the register showed a serving director who, as a matter of law, had ceased to hold office. Someone may have transacted on that. The register was not merely out of date — it was asserting something false, and the two time axes are what let you prove the extent and duration of it afterwards.

**What is stored differs too.** In the late-notification case there is a command from the company, a filed form in the archive, and an act. In the deemed cessation there is **no command at all** — nobody applied for anything. What exists is an inbound notification from another authority (archive, as evidence), an act recording the registry's acceptance of the fact, and an audit entry for the officer who acted on their own motion. A system that assumes every act begins with a command will have nowhere to put the second case.

This is the case that reaches **U9**: which events remove a person from office by operation of law, and what may the Registrar do on learning of them. Until that is answered, case 2 cannot be modelled properly.

---

## How to use this document

**With registry staff.** Read the story and the answers table aloud. Do not show them the code. Ask: is that what happens, and are those the answers you would need to give? Their corrections are the most valuable input the model will receive.

**With engineers.** These become the first golden test cases — acts in, expected views on both axes out, plus the preserved assertion. Per **ADR-090**, the narration is snapshot-tested so that a change in what the register would say shows up in a pull request for a domain expert to review.

**When adding a case.** The cases worth writing are the ones that fail. Registration of a transfer works in any architecture and teaches nothing. Rectification, restoration, subdivision, a disqualified appointment, a priority contest — those are where a model either holds or breaks.

### Cases still to write

| Case | Blocked on |
|---|---|
| Subdivision — one act, several resulting folios | **U5** |
| Restoration — deemed continuous existence | — |
| A priority contest between two lodgements | **U4** |
| An appointment refused on disqualification | — |
| A projection defect, where reconstruction and the assertion ledger disagree | **ADR-050** |

That last one is the case that justifies C10 to a sceptical reviewer. Worth writing early, even though it is the least likely to occur.