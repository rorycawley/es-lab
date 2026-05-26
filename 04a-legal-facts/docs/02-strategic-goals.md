# Strategic Goals and Objectives

*Status: Accepted.*
*Date: 2026-05-26.*

---

## Position in the Strategic Hierarchy

**Goals** are the strategic outcomes the Registry is pursuing in this period
(3–5 year horizon). They flow from the Mission and are shaped by the Drivers.

**Objectives** are the specific, measurable targets that define what achieving
each goal looks like. They are time-bound and unambiguous. An objective is
either met or not.

**KPIs** (Key Performance Indicators) are the ongoing measurement instruments
that show whether we are trending toward or away from each objective. An
objective answers "did we get there?"; a KPI answers "are we heading there?"
KPIs are measured continuously; objectives are assessed at a defined point.

The full hierarchy — Vision → Mission → Drivers → Goals → Objectives → KPIs —
is explained in [`01-vision-and-mission.md`](01-vision-and-mission.md).

---

## Statement of Intent

The Registry exists to serve two obligations that must never be compromised:
a legal obligation to the State — to maintain an authoritative, tamper-evident
record of registered companies under the Companies Registration Act — and a
public obligation to the commercial ecosystem — to make that record open,
reliable, and trustworthy.

These are not competing obligations. They reinforce each other. A register
that is authoritative but inaccessible fails the public. A register that is
accessible but unreliable fails the law. Every investment in this system must
serve both.

The Registry is not a form-processing office. It is the institution that
grants legal existence to companies. That responsibility shapes every priority
below.

---

## Strategic Goals and Objectives

### Goal 1 — Maintain the Integrity of the Legal Record

*The Register is the source of truth for company existence in this
jurisdiction. Every registered company must exist because a complete, lawful
process produced the fact of its registration — and for no other reason.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| No company exists in the Register without a `RegisteredCompanyCreated` event produced through a completed approval and registration process | Zero exceptions on continuous fitness function check (FF-001) |
| Every Registrar decision is made only in the `ReadyForDecision` state, after full examination, with no open requisitions | Zero out-of-state decisions; verified by fitness function (FF-006) |
| No Registration Number is ever reused after strike-off or dissolution | Zero reuse incidents; verified by database constraint and fitness function |
| Every registration decision is permanently and immutably recorded with the identity of the actor, the timestamp, and the causal chain | Clean result on every external audit; events are append-only (FF-003) |
| The audit log is available to and interpretable by external auditors without requiring knowledge of the event sourcing model | Clean external audit result within 12 months of go-live |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Data integrity incidents (count of events altered or deleted) | Zero — any non-zero value is a critical incident | Continuous (FF-003) |
| External audit findings on the Register (count of issues per audit) | Trending to zero | Per audit cycle |
| Fitness function FF-001 pass rate | 100% — any failure blocks the build | Every CI run |
| Fitness function FF-006 pass rate | 100% — any failure blocks the build | Every CI run |
| Time to detect and remediate a data integrity issue (hours) | Decreasing | Per incident |

**Linked business driver:** Legal certainty, Authoritative Register
**Linked characteristics:** Legal integrity, Auditability

---

### Goal 2 — Earn and Sustain Public Trust in the Register

*The public value of the Register is proportional to the public's confidence
in it. If businesses, investors, and the courts cannot rely on what the Register
says, the Registry has failed regardless of how efficiently it processes
applications.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| The public Register is available for inspection during all business hours | 99.9% availability of public search endpoints |
| Every registered company's particulars are current within the notification periods prescribed by the Registrar | Compliance rate for director and address change notifications above 90% within prescribed period |
| Struck-off and dissolved companies remain permanently visible in the Register with their status and the date of the change | Zero removals from public search after status change; verified by FF-008 |
| No confidential internal Registry data — examination notes, audit fields, command IDs, process manager state — is exposed through public endpoints | Zero field-level security incidents; verified by authorisation tests (ADR-0020) |
| Former company names are retained and protected for the prescribed period | Zero name reuse within the protected period; verified by approval checks (BR-AP-010) |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Public search endpoint availability (%) | ≥ 99.9% | Continuous (uptime monitoring) |
| Companies with overdue particulars as a % of the Register | Decreasing | Weekly |
| Information leakage incidents (count of confidential fields exposed publicly) | Zero — any non-zero value is a critical incident | Per release and continuous scanning |
| Fitness function FF-008 pass rate | 100% | Every CI run |

**Linked business driver:** Public trust and inspectability
**Linked characteristics:** Data consistency, Privacy and disclosure control

---

### Goal 3 — Process Applications Efficiently and Fairly

*An applicant who submits a lawful application deserves a decision within a
reasonable time. Examination protects the integrity of the Register; it must
not become a bottleneck that discourages legitimate registration.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| Every submitted application is assigned to an Examiner within 2 business days | 95% of applications assigned within 2 business days of submission |
| Applicants are informed at every state transition relevant to them: submission confirmed, requisition raised, decision made, registration completed | Zero missed notifications for transitions covered by user stories |
| Requisition rates decline year-on-year as applicant guidance improves | Year-on-year reduction in the proportion of applications requiring at least one requisition |
| No application sits in `ReadyForDecision` for more than 5 business days without a Registrar decision | 100% of `ReadyForDecision` applications decided within 5 business days |
| Rejected applications include a stated reason that gives the applicant a clear basis for correcting and resubmitting | 100% of rejections include a stated reason (BR-RA-011) |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Median time from submission to Examiner assignment (business days) | Decreasing toward ≤ 2 | Weekly |
| Median time from submission to final decision (business days) | Decreasing | Weekly |
| Requisition rate (% of applications requiring ≥ 1 requisition) | Decreasing year-on-year | Monthly |
| Notification delivery failure rate (%) | Trending to zero | Continuous |
| Applications in `ReadyForDecision` exceeding 5 business days without decision (count) | Zero | Daily |

**Linked business driver:** Controlled decision-making
**Linked characteristics:** Observability, Fault tolerance

---

### Goal 4 — Maintain Compliance Across Registered Companies

*Registration is not the end of the Registry's relationship with a company.
The Register is only as accurate as the notifications the Registry receives.
Proactive enforcement of filing obligations is part of the Registry's mandate.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| Annual return filing rate above 95% within the prescribed period | Measured per filing period; non-compliant companies flagged for strike-off consideration |
| Overdue annual returns trigger a follow-up within 10 business days | 100% of overdue returns generate a follow-up action within 10 business days |
| Director and address change notifications are filed within the prescribed period in 90% of cases | Measured per company per period |
| Strike-off is used consistently and transparently for companies that persistently fail their obligations | Strike-off actions are fully recorded with reasons and are publicly visible |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Annual return filing rate within prescribed period (% of registered companies) | ≥ 95% — declining rate triggers review | Per filing period |
| Director change notification compliance rate (% filed within prescribed period) | ≥ 90% | Monthly |
| Address change notification compliance rate (% filed within prescribed period) | ≥ 90% | Monthly |
| Overdue returns followed up within 10 business days (% of overdue returns) | 100% | Weekly |
| Strike-off actions per quarter (count) | Stable or declining — growth signals a systemic compliance problem | Quarterly |

**Linked business driver:** Identity confidence, Controlled decision-making
**Linked characteristics:** Legal integrity, Auditability

---

### Goal 5 — Build a System That Can Be Trusted and Audited Permanently

*This Registry will operate for decades. The decisions it records today must
be explainable in ten years. The system must be designed to survive failures,
schema evolution, and regulatory change without losing a single institutional
fact.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| No legal facts are lost in any infrastructure failure, including database failure, process crash, or network partition | Zero data loss incidents; verified by outbox, event store durability, and process manager recovery (ADR-0024) |
| The full read model can be rebuilt from events within a defined recovery time objective | Read model rebuild completes in under 30 minutes for current data volume (FF-002) |
| All commands are idempotent: retries by users, schedulers, or process managers never produce duplicate legal side effects | Zero duplicate registration numbers, payments, or notifications from retried commands (FF-004) |
| Event schemas evolve without breaking the ability to replay historical events | All event versions readable via evolvers at any point in time (ADR-0022) |
| The system passes an independent external audit without requiring the auditor to understand event sourcing | Clean audit result; human-readable audit log available (ADR-0018) |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Data loss incidents (count of legal facts unrecoverable after failure) | Zero — any non-zero value is a critical incident | Continuous |
| Read model rebuild time (minutes) | ≤ 30 minutes for current volume | Measured on each rebuild; verified by FF-002 in CI |
| Idempotency violations (duplicate side effects from retried commands) | Zero | Continuous (FF-004) |
| Event schema coverage (% of historical event types with a tested evolver) | 100% — gaps are a build failure | Every CI run |
| Mean time to recovery from infrastructure failure (minutes) | Decreasing | Per incident |

**Linked business driver:** Operational recoverability, Strong audit trail
**Linked characteristics:** Fault tolerance, Rebuildability, Idempotency, Auditability

---

### Goal 6 — Operate as a Self-Sustaining Institution

*The filing fee (§12A) is the mechanism by which the Registry funds its
operations without burden to the general exchequer. The Registry must remain
financially sustainable while keeping fees proportionate to the cost of a
lawful process.*

**Objectives:**

| Objective | Success Measure |
|-----------|----------------|
| Filing fee income covers the full operational cost of processing registration applications within 2 years of go-live | Cost per application ≤ filing fee revenue per application |
| Fee amounts are set by the Registrar by order and are adjustable without a code deployment | Configuration change only; verified by AC-AP-007-015 |
| The fee structure is reviewed annually and communicated to applicants clearly before payment is taken | Annual review completed; fee displayed before payment (BR-FE-005) |

**KPIs:**

| KPI | Direction | Measurement Frequency |
|-----|-----------|----------------------|
| Cost per application processed (operational cost ÷ applications decided) | Decreasing toward ≤ fee amount | Monthly |
| Filing fee income vs. operational cost ratio | Trending toward ≥ 1.0 | Monthly |
| Fee configuration deployments requiring a code change (count) | Zero — any code change for a fee amount is an architecture violation | Per fee change |

**Linked business driver:** All (operational sustainability enables all drivers)
**Linked characteristics:** Scope control

---

## Summary Cross-Reference

| Goal | Business Drivers | Architectural Characteristics |
|------|-----------------|------------------------------|
| 1 — Integrity of the legal record | Legal certainty, Authoritative Register | Legal integrity, Auditability |
| 2 — Public trust in the Register | Public trust and inspectability | Data consistency, Privacy and disclosure control |
| 3 — Efficient and fair processing | Controlled decision-making | Observability, Fault tolerance |
| 4 — Compliance across registered companies | Controlled decision-making, Identity confidence | Legal integrity, Auditability |
| 5 — Trustworthy and auditable system | Operational recoverability, Strong audit trail | Fault tolerance, Rebuildability, Idempotency |
| 6 — Self-sustaining institution | Operational recoverability | Scope control |
