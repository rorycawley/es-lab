# Registry System — Decision Record Register

Companion to the Guiding Specification. That document states **what must be true**; this one records **how we chose to make it true**. For the short orientation, see **The Essentials**.

**The rule that makes this register useful:** every decision record names the commitment or quality attribute it serves. An ADR that serves none is probably a preference, and should be challenged. This is the traceability that lets someone in 2040 see why the event log exists without reconstructing the argument — and that stops a load-bearing decision being reversed by someone who thought it was a stylistic one.

Every record cites specification IDs in its **Serves** column — **C** commitments, **Q** questions, **QA** quality attributes, **IMP** imposed constraints, **U** known unknowns. The scheme is defined in the Identifiers section of the specification, and those IDs are permanent.

**Status vocabulary**

| Status | Meaning |
|---|---|
| Decided | Agreed and in force |
| Proposed | Draft written, awaiting review |
| Open | Needs a decision before the affected work starts |
| Deferred | Deliberately postponed, with a named trigger |

---

## Foundation

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-001 | Event sourcing as the record model — an append-only log of acts is the source of truth | C1, C7 | Decided |
| ADR-002 | CQRS — read models are separate from and derived from the act log | C1, QA4 | Decided |
| ADR-003 | PostgreSQL as the store (externally mandated; recorded so the constraint is visible) | IMP1, QA3 | Decided |
| ADR-004 | Clojure, with a pure domain core and imperative shell | C9, QA1 | Decided |
| ADR-005 | One act per legally operative act — not per field, per workflow, or per aggregate save | C1, C3 | Decided |
| ADR-006 | Store separation — act record, assertion ledger, document archive, audit store and operational stores are distinct, with distinct retention and trust boundaries. Not one log with a discriminator | C1, C7, QA9 | Proposed |

## Record and storage

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-010 | Act encoding — plain, self-describing data; no framework-coupled or language-specific serialisation | QA6, CHO4 | Proposed |
| ADR-011 | Act schema versioning and evolution strategy | QA6, QA7 | Open |
| ADR-012 | Envelope separated from act — stream position, correlation and causation ids, schema version held outside the legal content | C1, Q1–Q13 | Proposed |
| ADR-013 | Physical layout: partitioning strategy for an append-only, never-pruned table | TGT6, TGT7 | Open |
| ADR-014 | Snapshots for reconstruction | QA4 | Deferred — trigger: rebuild benchmark misses the one-hour target |

## Ordering and concurrency

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-020 | Legal priority order recorded as data, never derived from insertion order or timestamps | C4 | Decided |
| ADR-021 | Total ordering mechanism for asynchronous consumers | C4 | Open |
| ADR-022 | Optimistic concurrency — subject plus version, uniquely constrained | C1 | Proposed |
| ADR-023 | Transaction isolation level for the command path | QA3 | Open |
| ADR-024 | Multi-subject acts — one act naming several subjects, versus several acts sharing a transaction id | C1, C4, U5 | Open |

## Derived views

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-030 | Authoritative projections written inside the act's transaction | QA3 | Decided |
| ADR-031 | Nominated views may be asynchronous, with a stated staleness bound and no legal standing | QA3 | Decided |
| ADR-032 | Bitemporal representation in projections | C2, C8 | Proposed |
| ADR-033 | Register-wide uniqueness (company name) enforced in the write transaction | C1 | Proposed |
| ADR-034 | Projection rebuild mechanism and its operational procedure | QA4 | Open |
| ADR-035 | Authoritative reads served so as to satisfy the non-staleness guarantee | QA3 | Proposed |

## Domain core

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-040 | decide / evolve / initial-state; `evolve` takes no rule regime parameter | C9 | Decided |
| ADR-041 | Refusals and requisitions returned as acts, never signalled by exception | C3, C6 | Decided |
| ADR-042 | Rule regime representation, versioning and binding to acts | C5, IMP3, NG4 | Proposed |
| ADR-043 | Cross-aggregate evidence assembled in the shell, passed as a value, version-asserted on write | C3 | Proposed |
| ADR-044 | Aggregate boundary — the register subject (company, folio), not the register | C1 | Decided |

## Assertions, audit and access

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-050 | Issued assertion ledger — official searches, certificates and extracts preserved with cut-offs, content and issue time | C10, QA2, U10 | Open |
| ADR-051 | Audit store held under a separate trust boundary, not writable by the principals it records | QA9 | Proposed |
| ADR-052 | Access logging — coverage, content, and what is deliberately excluded | QA9 | Proposed |
| ADR-053 | Entitlement model for gated registers, including beneficial ownership | QA9, U7 | Open |

## Operational records

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-055 | Command store — commands, idempotency keys and correlation ids held outside the act record; technical rejections logged here rather than as acts | C1 | Proposed |
| ADR-056 | Case and workflow state held outside the act record. Acts must be creatable with no originating command, since registrar-initiated and deemed-cessation acts have none | C1 | Proposed |

## Personal data and erasure

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-060 | Structural separation of erasable personal information from enduring legal facts | C7, QA10 | Proposed |
| ADR-061 | Erasure mechanism and key management | QA10 | Open |
| ADR-062 | Retention periods per data class and per store, including lawful maxima as well as minima | C7, U7 | Open — DPO owns |

## Documents and preservation

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-070 | Instruments referenced and hashed; held in an archival repository outside the act log | C3, QA11 | Proposed |
| ADR-071 | Archive fixity and retention guarantees | QA11 | Open |
| ADR-072 | Preservation unit contents and packaging (OAIS framing) | QA6 | Open |

## Resilience

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-080 | Replication topology and commit durability | QA5 | Proposed |
| ADR-081 | Backup and point-in-time recovery — disaster recovery only, distinct from domain history | QA5 | Proposed |

## Verification

| ID | Decision | Serves | Status |
|---|---|---|---|
| ADR-090 | Golden case harness — the cases in Worked Examples, with expected views on both axes and narration reviewed by domain staff | QA1, QA2 | Proposed |
| ADR-091 | Reconstruction determinism asserted on every build, comparing parsed structures rather than bytes | C8, QA1 | Proposed |
| ADR-092 | Rebuild fitness function — representative corpus, subject-local and register-wide views, trended | QA4, TGT6, TGT7 | Proposed |

---

## The ones that need thought

Most of the register is bookkeeping. These are the decisions where the options genuinely differ.

**ADR-021 — total ordering.** A sequence column does not give a safe watermark: two transactions can take numbers 100 and 101, the later one commit first, and a consumer that records its position at 101 will never see 100. Three options: record the transaction id and consume below the snapshot horizon; read commit order from the write-ahead log; or serialise appends outright. Given the derived write volume, the third is probably adequate and removes a class of bugs — but it should be a decision, not an accident.

**ADR-024 — multi-subject acts.** Subdivision and amalgamation break stream-per-subject. One act naming several subjects is faithful to the model but makes "the stream" a derived notion requiring an index. Several acts sharing a transaction id preserves simple streams but fragments one legal act into several records. **This depends on U5** — whether the statute treats subdivision as a single act or as a closure plus first registrations. Do not decide it before that answer arrives.

**ADR-050 — assertion ledger.** New, and its weight depends on U10. If an issued search can be relied upon in its own right, this is a first-class store with its own retention and integrity requirements. If only the register can be relied upon, it is a prudent operational record. Materially different scopes; get the legal answer first.

**ADR-006 — store separation.** The tempting shape with a single database is one events table with a type discriminator. It fails on four counts: reconstruction would have to filter, and that filter becomes a correctness dependency for every historical answer; four incompatible retention regimes collide; the audit trust boundary requires storage that the recorded principals cannot alter; and the act record stops being uniformly legally operative, which is the property that makes it evidence. Event sourcing as a technique across several stores is sound — one log for all of them is not.

**ADR-013 — partitioning.** The usual advice is to measure first, and for most decisions that is right. Here the cost is asymmetric: the table is append-only and never pruned, so retrofitting onto a large live national register is materially harder than establishing it at zero rows. Decide deliberately rather than defaulting to deferral.

**ADR-023 — isolation level.** Serialisable isolation is affordable at this write volume and removes a class of anomalies, at the cost of retry logic on serialisation failure. Worth a decision rather than inheriting the default.

**ADR-042 — rule regime representation.** The trap is building a general rules engine. Regime data may select and parameterise behaviour; it must not become an executable legal language. Build for one jurisdiction and abstract when there are two to compare.

---

## Sequencing

Not all of these are needed now.

**Before the first vertical slice:** ADR-006, 010, 012, 022, 040, 041, 055, 091. These shape the act record itself, and the act record is the part that cannot be corrected later. ADR-006 comes first: until the stores are separated, every subsequent decision about where something belongs has nowhere to land.

**Before the first production act:** ADR-020, 021, 030, 032, 060, 080, 090.

**Blocked on legal answers:** ADR-024 (U5), ADR-050 (U10), ADR-053 and ADR-062 (U7).

**Everything else** can follow demand. Derived views, archives and operational procedures are all correctable; the act record is not.

---

## Coverage index

Maintained here, not in the specification, so that adding a record changes one file. A commitment or quality attribute with no record against it is either not yet implemented or being satisfied by accident.

| Spec item | Decision records |
|---|---|
| C1 — act is the fact | ADR-001, 005, 006, 012, 022, 024, 030, 033, 044, 055, 056 |
| C2 — two time axes | ADR-032 |
| C3 — authority, actor, delegation, instance, evidence | ADR-041, 043, 070 |
| C4 — order is data | ADR-020, 021, 024 |
| C5 — regime bound to act | ADR-042 |
| C6 — decision recorded | ADR-041 |
| C7 — never altered; lawful erasure only | ADR-001, 006, 060, 062 |
| C8 — bitemporal reproducibility | ADR-032, 091 |
| C9 — reconstruction evaluates no rules | ADR-004, 040 |
| C10 — issued assertions preserved | ADR-050 |
| QA1 — evidential reproducibility | ADR-004, 090, 091 |
| QA2 — reproducibility of issued assertions | ADR-050 |
| QA3 — non-staleness | ADR-003, 023, 030, 031, 035 |
| QA4 — projection recoverability | ADR-002, 014, 034, 092 |
| QA5 — durability of acts | ADR-080, 081 |
| QA6 — interpretability | ADR-010, 011, 072 |
| QA7 — evolvability, new act type | ADR-011 |
| QA8 — evolvability, new jurisdiction | *no record yet* |
| QA9 — auditability of access | ADR-006, 051, 052, 053 |
| QA10 — erasability | ADR-060, 061 |
| QA11 — evidential integrity of documents | ADR-070, 071 |

**QA8 is uncovered.** That is expected rather than an omission — the specification commits to abstracting only when there are two jurisdictions to compare, so no record should exist until the second one arrives.

---

## Template

Keep them short. A page is plenty.

```
# ADR-0NN: <decision, stated as a decision, not a topic>

Status:   Proposed | Decided | Superseded by ADR-0MM
Date:     YYYY-MM-DD
Serves:   <specification IDs, e.g. C4, QA3>

## Context
What forces are in play. What makes this a real choice.

## Options
What was genuinely considered, and what each gives up.

## Decision
What we chose.

## Consequences
What this makes easy, what it makes hard, and what would
have to be true for us to revisit it.
```

The **Serves** line is not decoration. An ADR that cannot name a commitment or quality attribute is recording a preference, and preferences should be labelled as such so that nobody later mistakes one for a constraint.