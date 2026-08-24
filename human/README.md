# Registry System — Guiding Specification

*Status: draft for review. Capacity figures are engineering hypotheses, not measured facts. Items in "Known unknowns" require answers from counsel or registry staff before parts of this document can be relied upon.*

*Scope: this is a high-level specification. It states what must be true and why. Mechanism — schemas, storage design, replication topology, serialisation — belongs in decision records, which this document deliberately does not contain.*

---

## How to read this

**New to this?** Read **The Essentials** first — ten minutes, and it makes this document worth reading.

| If you are | Read | May skip |
|---|---|---|
| Counsel, or the Registrar | Problem statement, Glossary, What must be true, Known unknowns | Everything from Constraints onward |
| An architect or engineer | All of it | — |
| Reviewing operations or capacity | Glossary, then Deriving the write volume onward | — |

**What must be true** is the part that requires legal sign-off. Everything after **Constraints** is engineering judgement, recorded so it can be challenged. Anything marked *hypothesis* is an estimate awaiting measurement, not a requirement.

Mechanism — schemas, storage design, replication, serialisation — is deliberately absent. It lives in the **Decision Record Register**, which cites the identifiers used here.

Cases showing these rules operating end to end are in **Worked Examples**. If a commitment below seems abstract, that document is where to look for it in action.

---

## Problem statement

We are developing a system of record for a statutory registry — a corporate registry, a land title registry, or both — whose product is legally operative facts rather than current-state data.

The system must record what the registry did, under what authority, at whose instance, on what evidence, when that took effect and when it was recorded — such that any assertion the register has ever made can be reproduced and defended.

It must serve multiple jurisdictions whose laws differ and change, over a service life measured in decades, and the record must remain interpretable after the software that produced it has ceased to exist.

---

## Glossary

Terms are used in these senses throughout, and nowhere else in the document do they mean anything looser.

| Term | Meaning here |
|---|---|
| **Act** | Something the registry authoritatively did, under a statutory power, that asserts, recognises, determines or brings about a legal fact. Registrations, lodgements, refusals, requisitions and rectifications are acts. This is the unit in which the register's history is kept. |
| **Act record** | The registry's permanent and unalterable history of its own acts, and the thing from which every view of the register is computed. Everything in it is legally operative, which is what makes it evidence rather than a log. Working material — applications, correspondence, notifications, system records — is kept separately; see *What else is stored*. |
| **Subject** | The thing an act is about — a folio, a company, a person. Acts name one or more subjects. |
| **Effect time** | When a fact became legally true. May precede or follow the moment it was recorded. |
| **Record time** | The instant a fact became part of the authoritative record. |
| **Rule regime** | The body of law in force at a given time in a given jurisdiction, identified by version, and bound to each act performed under it. |
| **Reconstruction** | Working out what the register held at a chosen moment in the past, from the acts alone. Both cut-offs must be given: which moment in the world, and how much we knew by. Evaluates no rules. |
| **Projection**, or **derived view** | A stored, queryable form of the register computed from the acts. Disposable and rebuildable; never the truth. |
| **Authoritative view** | A derived view on which a legally consequential decision may be taken. Subject to the non-staleness guarantee. |
| **Issued assertion** | Something the registry told someone — an official search, a certificate, an extract — preserved as issued. |
| **Rectification** | Correction of the record where the register was wrong. Distinct from a change in the world, which is a transfer, an appointment, a cessation. |

---

## What must be true

Model commitments, stated in domain language. Each is unrecoverable if got wrong, because the information would never have been captured. Changing one requires legal review.

| ID | Commitment | What this means for us | What goes wrong without it |
|---|---|---|---|
| C1 | **The act is the fact; the register is computed from the acts.** | *Computed* is a statement about mechanism, not legal standing. Where registration is constitutive the register **is** the title — and it is constituted by the acts, which is exactly why the acts and not the register are what we keep. We never store "the current proprietor" as the thing that is true. Note the breadth of *act*: the registry does not always cause the underlying fact, but it always exercises authority over the record. | Registration becomes an overwrite. Intent, authority and ordering are never captured, and there is nothing to rebuild from when a defect corrupts the register. |
| C2 | **Every act records both when it took effect and when it was recorded.** | *Effect time* is when the fact became legally true. *Record time* is the instant it became part of the authoritative record. Effect time may precede record time (a resignation notified late) or follow it (a change registered to take effect next month). | We cannot distinguish "the proprietor changed" from "we had the proprietor wrong", and we cannot answer what the register stated when a purchaser searched it — which is the compensation question. |
| C3 | **Every act records under what authority, by whom and under what delegation, at whose instance, and on what evidence.** | Five things: the statutory provision; the officer who acted; the delegation entitling *that person* to exercise the power; who caused the act to be considered; and the instrument or application relied on, referenced and hashed. | We can show the register changed but not that we were entitled to change it. An act beyond an officer's delegation may be void even where the power plainly existed. |
| C4 | **Order is data.** | The sequence is recorded, not computed. Storage order, subject-stream order and legal priority order are distinct, and legal priority is never inferred from insertion order or timestamp comparison. | Priority contests, the most legally consequential computation in the system, come to rest on clock skew and non-atomic writes. |
| C5 | **Every act binds the rule regime version under which it was performed.** | Each act names the version of the legal regime in force. Registration under a 21-day protection period is a materially different act from one under 30 days, though it looks identical. | In ten years, "what did this act actually do?" is unanswerable, and law changes silently reinterpret old acts. |
| C6 | **Acts record the decision reached, not merely the inputs.** | Where judgement was exercised — class of title, whether a name is "too like" another, whether evidence sufficed — we store the conclusion, not the material that would let someone recompute it. | A rebuild in 2035 recomputes 2019's judgements under 2035's rules and produces a register that never existed. |
| C7 | **Acts are never altered. Information within an act is destroyed only where law expressly requires or permits, and only so that the act's structure, authority and legal consequence survive.** | Personal information that may lawfully require erasure is structurally separable from the enduring legal fact. Erasure removes the information; it does not remove the act or its consequence, and the erasure is itself recorded. | We destroy the evidence of our own guarantee, or we retain personal data we are not entitled to hold. Both are failures. |
| C8 | **A historical question with the same two cut-offs always yields the same answer.** Not merely today, but across software rewrites. Where information has been lawfully erased, the erasure is disclosed in its place. | Both cut-offs must be specified and the answer is then fixed. Not "what was true on 5 January" but "what did we, as at 20 January, understand to have been true on 5 January". | The register's answers drift as software changes. Evidence that cannot be reproduced on demand is not evidence. |
| C9 | **Reconstruction of past state evaluates no rules.** | Folding acts into a historical view runs no legal logic; it reads decisions already recorded. Rules are for deciding new acts, explaining old ones and simulating amendments — never for rebuilding history. | Rebuilding becomes re-deciding, so every change in the law silently changes the past. |
| C10 | **What the registry issued is preserved, as well as what it recorded.** | Every authoritative assertion the registry issues — official search, certificate, extract — is preserved with its cut-offs, result and issue time. Reconstruction shows what the register *should* have said; this shows what we *actually told someone*. | A projection defect corrupts a search in 2027 and is fixed in 2032. Replay now produces the correct answer, and we cannot prove what the purchaser was actually shown — which is the only thing the reliance claim turns on. |

### Clarifications

Kept out of the commitments so those stay short enough to be read in full by someone who does not write software.

**An act is a registry-significant fact, not a technical event.** It takes three forms: things the registry *did* (a registration), external facts the registry *authoritatively accepted* (a director's death), and *determinations* the registry reached (a company is in default). All are acts. Not all are exercises of power over the world — but all are exercises of the registry's authority over the record.

**Two time axes are foundational; other times are attributes.** Effect time and record time are structural. Lodgement time, decision time, priority time and commencement time are attributes of particular act types, not further axes.

**Three operations, kept separate.**

| Operation | Inputs | Rules evaluated |
|---|---|---|
| Reconstruction | acts | none |
| Audit | act + authority + regime + evidence | as explanation only |
| Simulation | state + proposed rules | yes, hypothetically |

**Corollary of C9.** If a rule-dependent determination carries legal consequence, it is recorded as a decision in an act — never computed in a projection. A projection requiring rules to reach a legally consequential conclusion is a symptom of a missing act.

---

## The questions every act must answer

An act is a legal artifact, and legal artifacts have a fixed set of questions that must be answerable about them. These are not a schema — they are the questions a lawyer would ask in a witness box. **The schema is derived from them, not designed.**

Every act must **explicitly resolve** all thirteen (Q1–Q13). "Not applicable" is a valid answer where a regime genuinely does not apply; "unknown because we failed to capture it" never is.

| ID | Question | Why it matters | Example answer |
|---|---|---|---|
| Q1 | **What was done?** | Names the legally operative act in statutory language rather than describing a data change. Makes the record readable as an account of registry action instead of a database diff. | `:change-of-officers-registered` — not `directors-updated` |
| Q2 | **To what was it done?** | Identifies the subject or subjects. An act may name several — a subdivision names the parent folio and its children in one act. | company `IE-624413` |
| Q3 | **Who did it?** | The act was performed by a person exercising authority. Their identity is needed for appeal and for disciplinary review. | registrar `REG-0042` |
| Q4 | **Under what delegation?** | Q6 establishes that the *registry* held the power; this establishes that *this actor* was entitled to exercise it. Recorded rather than looked up, because entitlements change, and because a delegation later found invalid must be answerable by query. | delegation `DEL-2024-017` |
| Q5 | **At whose instance?** | Distinguishes an act the company filed from one a court directed from one the Registrar performed of their own motion. Different appeal routes, different evidential requirements. | `:company-filed`, `:court-order`, `:registrar-own-motion` |
| Q6 | **Under what power?** | The statutory provision that authorised the act. This question is also the filter that keeps process telemetry out of the record. | Companies Act 2014, s.[x] |
| Q7 | **Under what regime?** | Which version of the law was in force. The same provision carries different periods and thresholds over time and across jurisdictions, so the provision alone does not fix the meaning. | `ie-companies-2026.05.02` |
| Q8 | **On what evidence?** | The instrument or application relied upon, referenced and hashed. Establishes what was before the registrar without the document living in the record. | application `APP-77120` (Form B10); consent `DOC-31200`, `sha256:be31…` |
| Q9 | **When did it take effect?** | When the fact became legally true. Supplied by the domain, not read from the clock — a resignation takes effect on the date notified, not the date filed. | `2026-07-30` |
| Q10 | **When was it recorded?** | The instant it became part of the authoritative record. | `2026-08-11T09:04:12Z` |
| Q11 | **What legal priority applied?** | Priority is order, recorded as a fact. This is the *legal* ordering, not a storage position. In a land registry it is the most legally consequential field on the act. | priority running from lodgement `2026/012345` at `2026-02-27T09:15:00Z`. In a corporate registry with no priority regime: `:not-applicable` |
| Q12 | **What was decided?** | The judgement the registrar reached, stored so it is never recomputed under later rules. Refusals and requisitions are recorded here, as acts in their own right. Where the statute attaches consequences to the *ground*, the ground is an enumerated value from the regime — never free text. | `{:outcome :registered}` or `{:outcome :refused :grounds [:name-too-like]}`. A rectification records `:ground :clerical-error` or `:ground :fraud`; consequences and appeal rights differ. |
| Q13 | **What did it change?** | The legal consequence, stated as such rather than as a field diff. | appointment of `P-9014` as director from 2026-07-30; cessation of `P-8802` on the same date |

Q3–Q8 discharge C3. Q9–Q10 discharge C2. Q11 discharges C4. Q7 discharges C5. Q12 discharges C6.

### The act is not the envelope

Stream positions, expected versions, message ids, correlation and causation ids, schema versions and encoding metadata are all necessary. **None of them is part of the act.**

> The envelope exists to make the software reliable. The act exists to make the institution intelligible.

Keep them separable. A lawyer in a witness box does not care about stream position 41, and the moment machinery starts appearing among the thirteen answers, the discipline that makes them useful is gone.

### What the questions do for us

**Q6 is the filter.** No statutory power, no act. `NotificationSent`, `AddressValidated`, `PaymentReceived` cannot answer it and belong in operational logging.

**Q6 and Q9 settle granularity.** One act per legally operative act — not per field, not per workflow, not per aggregate save.

- *Too fine:* separate `OwnerNameChanged` and `OwnerAddressChanged` fail Q6. There is no power to change an owner's name in isolation; the act was a registration of transfer, and decomposing it evaporates the legal meaning.
- *Too coarse:* one `DealingProcessed` spanning lodgement through registration fails Q9. There is no single effect time.

**They are a discovery technique.** Resolve all thirteen for each act type before writing code. Questions that cannot be answered — usually 6 or 12 — are genuine gaps in domain understanding, and finding them now rather than in year three is the point.

### The acceptance test

> Could this record be read aloud in court as an account of what the registry did and why it was entitled to do it?

If yes, it is an act. If it reads as a description of a data change, the mechanism has been modelled and the meaning lost.

---

## What else is stored

Event sourcing is used throughout the system. **Most stored events are not acts.** Acts are one store among several, and the boundary between them is load-bearing: an act record containing everything the system does is an activity stream, and an activity stream is not evidence.

| Store | Contains | Retention |
|---|---|---|
| **Act record** | Acts, as defined by C1 and filtered by Q6 | Permanent |
| **Assertion ledger** | What the registry told someone — searches, certificates, extracts (C10) | Statutory period |
| **Document archive** | Instruments, maps and supporting evidence, referenced and hashed by acts | Statutory period, with fixity guarantees |
| **Audit store** | Reads, authorisation decisions, administrative actions. Separate trust boundary — not alterable by the principals it records | Bounded; see U7 |
| **Operational stores** | Commands, case and workflow state, technical rejections, telemetry | Short; prunable |

### The filter

> **Was a statutory power exercised, and would the register be different if this had not happened?**

Both must be yes. Q6 is where this is applied.

### Borderline cases

The examples that decide it, since the rule is easier to state than to apply.

| Candidate | Act? | Why |
|---|---|---|
| Registration of a transfer | **Yes** | Power exercised; the register changes |
| Refusal of a dealing | **Yes** | Exercised under power, appealable, legally consequential |
| Requisition issued | **Yes** | Power exercised; consequences for the application |
| Lodgement, where it fixes priority | **Yes** | Changes the legal position, even though nothing is yet registered |
| Person records merged after identity resolution | **Yes** | A determination made under a power, with recorded grounds |
| Official search **with** priority protection | **Yes**, and also an assertion | Confers protection — dual nature |
| Plain official search | No — assertion | The registry reported; it decided nothing |
| Fee received | No — evidence | A precondition, not an operative act |
| Rule regime deployed | No — audit | Authority over the system, not over the register |
| Projection rebuilt | No — audit | Derived views are disposable by design |
| Malformed submission rejected | No — operational | No power exercised; the registrar never considered it |
| Reminder that a return is due | No — operational | No power, no consequence |
| Notification of registration sent | No — operational | The legal consequence already occurred |
| Officer assigned to a case | No — operational | Internal workflow |

### Why the separation is not merely tidiness

**Evidential value.** An act record whose entries are uniformly legally operative can be read as an account of registry action. Mix in notifications and retries and it becomes something a court has to be talked through.

**Reconstruction.** Folding the act record must not require filtering. If non-acts are present, the filter becomes a correctness dependency, and a filter bug silently changes history.

**Retention.** Acts are permanent. Access logs have a lawful **maximum** as well as a minimum. Operational data should be pruned. One store cannot serve three retention regimes.

**Trust boundary.** The audit store must not be alterable by those it records — which means separate storage with separate credentials, not a separate stream in the same table under the same account.

> Using event sourcing as the storage technique for several stores is sound. Using **one log** for all of them is not.

---

## Constraints — imposed

Things we cannot change. If an item here turns out to be a choice, move it to the next section — a future architect must know whether they are trapped or merely inheriting a decision.

| ID | Constraint | Consequence for the design |
|---|---|---|
| IMP1 | PostgreSQL as the store | Mandated externally. A single ACID transaction can span the act and its projections; consistency is available and need not be traded away |
| IMP2 | Authoritative views strongly consistent; only nominated views eventually consistent | Rules out asynchronous-by-default architectures for anything a person relies on |
| IMP3 | Multiple jurisdictions; law varies and changes | Rule regime identity, version and provenance must be data, and bound to every act |
| IMP4 | Service life measured in decades | The record must outlive the code, the team and the vendor |
| IMP5 | EU data protection applies | Immutability and erasure must be reconciled structurally, not retrofitted |

---

## Constraints — chosen

Each gives something up to gain something specific.

| ID | We give up | We gain |
|---|---|---|
| CHO1 | Mutating acts | Free sharing, no read coordination, evidential integrity |
| CHO2 | Ambient rule lookup — regimes are passed in explicitly | Auditability of why an act was permitted; ability to simulate an amendment before commencement |
| CHO3 | Rules inside reconstruction | Historical state that cannot drift as law changes |
| CHO4 | Framework-coupled serialisation | Interpretability without our code |
| CHO5 | Storing current state as truth | Cheap correction of our own defects; any historical view derivable |
| CHO6 | Inferring order from timestamps | Priority as a recorded fact rather than a computation over clocks |
| CHO7 | I/O anywhere in the domain core | The whole domain testable in memory |
| CHO8 | A general rules engine | Behaviour stays readable code; only regime identity, version and statutory parameters are data |
| CHO9 | Language choice — Clojure | Data-oriented core; acts as plain values; pure decision functions |

### Rules as data — the boundary

| Concern | Treatment |
|---|---|
| Rule regime identity, version, provenance | **Must** be data |
| Configurable statutory parameters | **Should** be data |
| Domain behaviour | **May** remain code |
| Which regime governed an act | **Must** be bound to the act |

> Regime data may select and parameterise behaviour. Regime data does not itself constitute an executable general-purpose legal rules language.

### Configurable, versus a different product

Not every jurisdictional difference is a parameter.

- **Configurable:** which acts exist; preconditions; protection periods; rectification powers; access entitlements; retention.
- **A different product:** whether registration is constitutive or declaratory. Deeds registration and title registration differ in what is legally operative. A configuration surface spanning both will express neither correctly.

---

## Quality attributes

Stated as observable qualities. **How** each is achieved is an architectural decision recorded elsewhere; this section deliberately does not prescribe mechanism. Numeric targets appear later, after the volume derivation that justifies them.

**QA1 — Evidential reproducibility.** The same bitemporal question, asked years apart across software upgrades, returns the same answer. *Observable:* semantically identical reconstruction over a fixed corpus, asserted on every build.

**QA2 — Reproducibility of issued assertions.** For any authoritative assertion the registry issued, the exact content issued can be produced, independently of what reconstruction now says the answer should have been.

**QA3 — Non-staleness of authoritative views.** *Observable:* given an act committed at T, an authoritative view produced after T cannot return a state preceding that act.

**QA4 — Projection recoverability.** Loss or logical corruption of any derived view is repaired solely from authoritative acts, within the service restoration period.

**QA5 — Durability of acts.** *Observable:* a committed act survives loss of any single database node with no loss of committed acts.

**QA6 — Interpretability without the system.** An analyst holding the preserved record and no code can determine what each act did and under what power. *Observable:* the preservation unit includes act log, canonical schemas, act-type definitions, controlled vocabulary, regime definitions, authority references, encoding specification and schema evolution history. OAIS (ISO 14721) is the intended framing.

**QA7 — Evolvability — new act type.** A statutory amendment introduces a new act. *Observable:* no migration of existing acts; reconstruction output unchanged.

**QA8 — Evolvability — new jurisdiction.** *Observable:* new jurisdiction-specific behaviour is addable without changing the meaning of existing acts or the behaviour of existing jurisdictions. We do not claim a new jurisdiction requires no new code — a new jurisdiction may reveal a genuinely new domain concept, and that is reality teaching us something rather than an architecture failure.

**QA9 — Auditability of access.** *Observable:* 100% of entitlement-gated reads record requester, entitlement relied on and time; the log is not alterable by the accessing principal.

**QA10 — Erasability.** *Observable:* a lawful erasure obligation is satisfiable without destroying the act's structure, authority or legal consequence, and the erasure is itself recorded.

**QA11 — Evidential integrity of referenced documents.** *Observable:* an instrument referenced by an act is produced years later and proved to be the one relied upon. A hash proves identity only if the artefact survives; the archive needs its own fixity and retention guarantees.

---

## Non-goals

- **NG1** Not a document management system — instruments are referenced and hashed; the archive is a separate concern with its own guarantees
- **NG2** Not a high-throughput OLTP system
- **NG3** Not eventually consistent by default
- **NG4** Not a general rules engine — one jurisdiction first; abstract when there are two to compare
- **NG5** Not a case-management or workflow product, though it must interoperate with one
- **NG6** Not a replacement for the statute — this model describes the register, it does not define it

---

## Known unknowns

Written down so they do not become silent assumptions. Each needs an answer from counsel or registry staff, not from engineering.

| ID | Question |
|---|---|
| U1 | Is registration constitutive of title, or declaratory of a transfer effected by the instrument? |
| U2 | Is compensation a function of the registry, or of the courts or a separate body? |
| U3 | What defined term does the statute use in place of "act"? |
| U4 | Does priority protection exist, by what mechanism, and for how long? |
| U5 | Is subdivision one act, or a closure plus several first registrations? |
| U6 | Are verified person identifiers available, or is identity resolution a permanent operational burden? |
| U7 | What retention obligations attach to access logs and issued assertions — maxima as well as minima? |
| U8 | Which historical acts under repealed legislation must be representable? |
| U9 | Which events remove a person from office by operation of law, and what may the Registrar do of their own motion on learning of them? |
| U10 | Can an issued official search be relied upon in its own right, or only the register it reported? |

**U1 is the priority.** It can invalidate parts of the model rather than merely refine them. **U10** determines how much weight C10 has to carry.

---

## Deriving the write volume

The derivation matters more than the figures. Substitute real numbers from the registry's published annual report.

**Corporate registry, small jurisdiction** (~300k entities): roughly one filing per entity per year for the annual return, plus one or two for changes, incorporations and dissolutions. Approximately **1M acts/year**.

**Corporate registry, large jurisdiction** (~5M entities): approximately **12M acts/year**.

**Land registry:** materially lower. ~300k dealings/year small, ~5M large.

Spread over roughly 2,500 business hours (9M seconds):

| Registry | Mean writes/sec | Peak (×50 for deadline clustering) |
|---|---|---|
| Small corporate | 0.11 | ~6 |
| Large corporate | 1.3 | ~65 |
| Small land | 0.03 | ~2 |
| Large land | 0.55 | ~28 |

The ×50 factor accounts for annual-return deadlines, financial year ends, agent bulk submission and, in land, completions clustering before weekends and ahead of duty changes.

**This is the number that licenses a simple design.** Most event-sourcing complexity exists to solve throughput problems this system does not have.

---

## Reads are the opposite story

Corporate registries are heavily read by automated KYC, AML and credit-reference access — plausibly 10⁸–10¹⁰ reads per year for a large jurisdiction.

But **authoritative reads are a small fraction**, on the order of 0.1–1%.

That split is the scaling strategy: authoritative views served with the non-staleness guarantee, everything else with staleness bounded, stated in the response, and explicitly non-legal.

**Trap to avoid:** replication is asynchronous by default. Moving official searches to a replica to "scale reads" reintroduces staleness at precisely the point where none was promised.

---

## Filled quality attribute targets

Distinguish: known statutory requirement ≠ business forecast ≠ engineering assumption ≠ measured capacity. Everything marked *hypothesis* stands until measured.

| ID | Attribute | Target | Status |
|---|---|---|---|
| TGT1 | Write throughput | 100 acts/sec sustained, 500 burst | Hypothesis |
| TGT2 | Authoritative read | p50 < 50ms, p99 < 300ms | Hypothesis |
| TGT3 | Act commit latency | p50 < 100ms, p99 < 500ms | Hypothesis |
| TGT4 | Non-authoritative read | p99 < 200ms; staleness bound < 5s, stated in response | Hypothesis |
| TGT5 | Bitemporal historical query | p99 < 2s | Hypothesis |
| TGT6 | Single projection rebuild | < 1 hour at 50M acts | Hypothesis — must be measured |
| TGT7 | Full rebuild, all projections | < 4 hours; must fit a maintenance window | Hypothesis — must be measured |
| TGT8 | RPO, committed acts | 0 for loss of any single node | Design requirement |
| TGT9 | RTO | 4 hours | Requires confirmation |
| TGT10 | Availability | 99.9% in service hours; 99.5% overall | Requires legal input — see below |
| TGT11 | Reconstruction determinism | 100%, semantically identical, asserted every build | Commitment |
| TGT12 | Non-staleness of authoritative views | Zero tolerance | Commitment |
| TGT13 | Issued assertions retrievable | 100%, for the statutory retention period | Commitment |
| TGT14 | Access logging coverage | 100% of entitlement-gated reads | Statutory |

**On determinism:** compare parsed data structures, not bytes. Byte-identity would make incidental serialisation choices — key ordering, whitespace — an eternal contract, which is not what is meant.

---

## Storage

At approximately 5 KB per act: 50M acts ≈ 250 GB, perhaps **1 TB with indexes and derived views**. Growth of 5–60 GB/year, never pruned. Issued assertions add a further stream, likely larger in count than acts and smaller per record.

Instruments, maps and supporting documents will be 10–100× larger. They live in the archival repository, not the act record. Hash and reference.

Physical layout — partitioning, tablespaces, snapshots — is mechanism and belongs in a decision record. Note only the asymmetry: the record is append-only and never pruned, so retrofitting a physical strategy onto a large live national register is materially harder than establishing one at zero rows. Record the reasoning; do not silently defer it.

---

## The rebuild number is the one to pressure-test

Everything else in the capacity section has slack. Rebuild time does not, because it is the remedy when a defect corrupts a derived view — and a remedy that takes thirty hours is not a remedy.

The chain of reasoning is short and worth stating:

> Acts are sacred → derived views are disposable → therefore rebuild is a critical recovery operation → therefore rebuild performance is an architectural fitness function, not an optimisation.

**Hypothesis:** if sustained reconstruction reaches roughly 20k acts/sec single-threaded, 50M acts is approximately 40 minutes. **This number is assumed, not measured.** It is load-bearing for the one-hour target and should be the first thing benchmarked.

Subject-local views parallelise well, since folios and companies are independent. **Register-wide views do not** — name uniqueness, cross-subject reporting and priority ordering behave differently. Benchmark both separately.

Make it a standing fitness function: a representative 50M-act corpus, a representative subject-local view and a representative global one, run on a schedule with results trended over time. If the real figure is an order of magnitude worse, snapshots become a first-class part of the design — a decision better taken in month two than in year three.

---

## Two I'd flag as genuinely uncertain

**Availability during service hours may be a legal question, not an engineering one.** Where priority runs from time of lodgement, an outage can change legal outcomes rather than merely inconvenience users. Some jurisdictions provide for this — deemed lodgement, extended protection periods, registrar's discretion. Establish whether yours does before treating 99.9% as an engineering target.

**Retention of access logs and issued assertions has a maximum as well as a minimum.** Indefinite retention of who searched what is itself a data protection exposure, and in a land registry the fact that a party searched a particular title may be commercially sensitive. Getting the period wrong is a compliance finding in either direction. The DPO owns this; engineering should not choose a number.

---

## Document status

| Section | Owner | Change process |
|---|---|---|
| Problem statement, What must be true | Registrar / counsel | Legal review |
| The questions every act must answer | Architecture, with domain review | Documented decision |
| Constraints, Non-goals | Architecture | Documented decision |
| Quality attributes | Architecture, with statutory input | Documented decision |
| Capacity sections | Engineering | Measurement supersedes hypothesis |
| Known unknowns | Shared | Closed by written answer, dated |

The commitments are the constitution and must remain short enough to be read in full by someone who does not write software. Precision belongs one layer down, in the act catalogue and the decision records.

---

## Appendix — Identifiers

Every referenceable item in this document carries a stable ID. Decision records cite these IDs in their **Serves** line, so that any mechanism decision can be traced to the commitment or quality it exists to satisfy.

| Prefix | Applies to | Range |
|---|---|---|
| **C** | Commitments — what must be true | C1–C10 |
| **Q** | Questions every act must answer | Q1–Q13 |
| **IMP** | Constraints — imposed | IMP1–IMP5 |
| **CHO** | Constraints — chosen | CHO1–CHO9 |
| **QA** | Quality attributes | QA1–QA11 |
| **NG** | Non-goals | NG1–NG6 |
| **U** | Known unknowns | U1–U10 |
| **TGT** | Capacity and performance targets | TGT1–TGT14 |

**IDs are permanent.** An item that is withdrawn keeps its ID, marked as withdrawn; its number is never reissued. Otherwise an ADR written in 2029 and read in 2041 will cite something that has silently become a different requirement.

**Links run one way.** Decision records reference specification IDs; this document does not list the ADRs that implement each item. The reverse index is maintained in the Decision Record Register, so that adding an ADR changes one file rather than two.