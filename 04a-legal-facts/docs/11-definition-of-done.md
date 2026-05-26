# Definition of Done and Acceptance Protocol

*Status: Proposed — requires customer sign-off before development begins.*
*Date: 2026-05-26.*

---

## Purpose

This document defines, in advance and bilaterally, what "done" means at every
level of the project — for a single user story, for a release, and for the
project as a whole. It also defines the acceptance protocol: the structured
process by which the delivery team and the customer collectively agree that the
evidence is sufficient and the work is complete.

Agreeing this document before work begins is the point. Once it is signed, the
question "is it done?" has a shared, unambiguous answer. It cannot be argued
after the fact.

---

## Why This Project Can Be Proven Complete

Most software projects struggle to prove completion because requirements and
tests are not formally linked. This project is structured so that every
requirement traces to a test and every test traces to a requirement:

```
Act section (§X)
  → Business rule (BR-XX-NNN)
    → User story (US-XX-NNN)
      → Acceptance criterion (AC-XX-NNN-NNN)
        → Test named after the AC ID
          → CI result
```

This means the CI pipeline, when all tests pass, is itself a proof of
compliance with the Act. The acceptance protocol below describes how that
proof is presented and reviewed.

---

## Level 1 — Story Done

A user story is done when all of the following are true. This checklist is
applied by the delivery team before a story is presented for acceptance.

| # | Criterion | Evidence |
|---|-----------|----------|
| 1 | Every acceptance criterion for the story has a corresponding named test | Test file — test names match AC IDs exactly (e.g. `AC-AP-007-001-...`) |
| 2 | All story tests pass in CI on the trunk branch | CI build report — green |
| 3 | All fitness functions (FF-001–FF-014) continue to pass | CI build report — no FF failures |
| 4 | The story's traceability metadata is complete: `§` reference, `BR-` reference, `Characteristics:`, `Fitness functions:`, `ADRs:` | User story entry in `08-user-stories.md` |
| 5 | No acceptance criterion references a parked concern | `FF-010` passes |
| 6 | New data fields introduced by the story carry an explicit classification label | `FF-012` passes; classification table in `07-business-rules.md` updated if needed |

---

## Level 2 — Release Done

A release is done when all Level 1 criteria are met for every story in scope,
plus all of the following.

| # | Criterion | Evidence |
|---|-----------|----------|
| R1 | All user stories in the release scope are at Level 1 done | Traceability report (see below) |
| R2 | All fitness functions FF-001 through FF-014 pass on the release candidate | CI build report |
| R3 | The audit log records the correct actor, action, target, description, and timestamp for each story's key events | Acceptance test run — audit log entries verified against expected values |
| R4 | The four-eyes rule is enforced: FF-014 passes | CI build report |
| R5 | Data classification is enforced: FF-011, FF-012, FF-013 all pass | CI build report |
| R6 | The public register returns only Public-classified fields for every registered company | FF-012 result + manual spot-check during demo |
| R7 | The event store is append-only and cannot be mutated by the application | FF-003 result + database permission audit |
| R8 | Read models can be rebuilt from events (drop and replay) | FF-002 result demonstrated in acceptance environment |
| R9 | No breaking change has been introduced to a previously accepted story | Regression test suite passes in full |

---

## Level 3 — Project Done

The project is done when all releases in scope are at Level 2 done and the
following are satisfied.

| # | Criterion | Verified By |
|---|-----------|------------|
| P1 | Every section of the Act that is in scope has at least one business rule (BR-) and at least one user story with a passing test | Traceability report — Registrar sign-off |
| P2 | Every architectural characteristic has at least one passing fitness function | Traceability matrix in `06-architectural-characteristics.md` — Technical Lead sign-off |
| P3 | Every ADR has been implemented or explicitly deferred with a recorded reason | ADR index in `adrs/README.md` — Technical Lead sign-off |
| P4 | The audit log access controls are verified: only `audit_reader` role can read `audit_log`; no API endpoint exposes audit log rows | FF-013 + database permission audit — Registry Authority sign-off |
| P5 | External auditors have been given read-only access to the `audit_log` table and have confirmed the entries are legible and sufficient | Written confirmation from external auditor |
| P6 | Parked scope is named, visible, and free of any active implementation | FF-010 passes; parked items listed in `03-business-drivers.md` |

---

## The Acceptance Protocol

The acceptance protocol is the ceremony that produces the evidence and
collects the sign-off. It runs at the end of each release.

### Step 1 — Traceability Report (delivery team, 2 days before review)

The delivery team produces a traceability report covering every user story
in the release. For each story the report shows:

- Story ID and title
- Act section(s) referenced
- Business rules exercised
- Acceptance criteria
- Test names and CI pass/fail status
- Fitness functions exercised
- Result: **In scope and passing** / **In scope and failing** / **Parked**

Any story that is in scope and failing blocks the review. The review does not
proceed until all in-scope stories are passing.

### Step 2 — Acceptance Review (joint session, delivery team + customer)

A structured session, typically two to three hours, run in the acceptance
environment (not production). The agenda is fixed:

1. **CI evidence** (15 min) — The delivery team opens the CI build report for
   the release candidate. Every test category is shown: unit, integration,
   behavioural, fitness function, contract. All must be green before proceeding.

2. **Traceability walkthrough** (30 min) — For a representative sample of
   stories (chosen by the customer), the delivery team demonstrates the chain:
   Act section → business rule → user story → acceptance criterion → named
   test → CI result. The customer selects stories at random from the
   traceability report; the delivery team must be able to navigate the chain
   for any story chosen.

3. **Functional demonstration** (60–90 min) — The delivery team demonstrates
   each user story in scope against its acceptance criteria. Demonstrations are
   performed in the acceptance environment against live data. The customer
   confirms each story against its stated criteria — not against informal
   expectations formed after the story was agreed.

4. **Audit trail demonstration** (20 min) — The delivery team demonstrates
   the audit log for the actions performed during the functional demonstration.
   The customer confirms the entries are complete, legible, and correctly
   attribute actions to actors. The four-eyes rule is demonstrated live
   (FF-014).

5. **Data classification spot-check** (15 min) — The customer selects one or
   more company records and the delivery team demonstrates that the public API
   returns only Public-classified fields, while Restricted and Confidential
   fields are not exposed.

6. **Open issues** — Any story the customer does not accept is recorded as a
   defect with a specific, written reason traceable to an acceptance criterion.
   Informal or post-hoc expectations are not valid reasons for non-acceptance.

### Step 3 — Sign-off

Following the review, the sign-off sheet is completed. Sign-off is **not** a
gesture of satisfaction — it is a formal statement that the stated evidence
was reviewed and found sufficient against the agreed criteria.

| Signatory | Role | Signs Off On |
|-----------|------|-------------|
| Registrar (or delegated authority) | Customer — domain | Functional correctness: user stories and acceptance criteria |
| Registry Authority | Customer — legal/compliance | Act traceability (P1) and audit log sufficiency (P4, P5) |
| Technical Lead (delivery) | Delivery — architecture | Fitness functions, ADR implementation, classification enforcement |
| Project Lead (delivery) | Delivery — delivery | Completeness of scope, parked items list |

Sign-off is given **per release**. A sign-off on an earlier release does not
imply acceptance of regression in a later release.

Any signatory may withhold sign-off. Withholding requires a written statement
citing the specific criterion (by ID) that is not met. Disagreement about
scope or expectations that were not recorded in a user story or acceptance
criterion is not grounds for withholding.

---

## Defect Classification

Not every issue found during the review blocks acceptance. Issues are
classified as follows:

| Class | Definition | Effect on Sign-off |
|-------|-----------|-------------------|
| **Blocking** | An acceptance criterion is demonstrably not met | Blocks sign-off; must be resolved before re-review |
| **Major** | A fitness function fails | Blocks sign-off; must be resolved before re-review |
| **Minor** | Behaviour deviates from the story but the AC is arguably met | Logged; customer decides whether it blocks |
| **Observation** | A concern not traceable to any agreed criterion | Logged as a new story candidate; does not block current sign-off |

---

## What Is Not Grounds for Blocking Sign-off

To prevent scope creep at the acceptance stage, the following are explicitly
not valid reasons to withhold sign-off:

- Requirements that were not recorded in a user story or business rule before
  development began
- Preferences about implementation approach that are not captured in an ADR
- Features in the parked scope list (these are named, visible, and deliberately
  deferred — see `03-business-drivers.md`)
- Performance concerns not expressed as a measurable acceptance criterion

If any of these are raised during the review, they are recorded as new user
story candidates and scheduled into a future release.

---

## Traceability Report Format

The traceability report is a flat table, one row per acceptance criterion:

| AC ID | Story ID | Story Title | Act Section | Business Rule | Test Name | FF IDs | CI Status |
|-------|----------|-------------|-------------|---------------|-----------|--------|-----------|
| AC-AP-007-001 | US-AP-007 | Applicant submits active draft | §8, §12A | BR-SB-001, BR-FE-001 | `AC-AP-007-001-submit-active-draft` | FF-001, FF-006 | ✓ Pass |
| … | … | … | … | … | … | … | … |

This table is generated from the user stories in `08-user-stories.md` and the
CI test results. Any AC with no corresponding named test, or whose test is
failing, appears as a gap.

---

## Supporting Documents

| Document | Role in Acceptance |
|----------|--------------------|
| [`00-companies-registration-act.md`](00-companies-registration-act.md) | Source of legal requirements; traceability anchor |
| [`07-business-rules.md`](07-business-rules.md) | Enforceable rules; BR IDs in the traceability report |
| [`08-user-stories.md`](08-user-stories.md) | User stories and acceptance criteria; the agreed scope |
| [`06-architectural-characteristics.md`](06-architectural-characteristics.md) | Fitness functions FF-001–FF-014; what the CI build verifies architecturally |
| [`09-testing-strategy.md`](09-testing-strategy.md) | Test layer definitions; what runs where and when |
| [`10-architecture.md`](10-architecture.md) | Architecture decisions verified during acceptance (audit log access, classification) |
