# From Requirements to Architecture

### A connecting model for a DDD, event-sourced, modular monolith

*Corporate registry worked example. Functional core / imperative shell, Use-Case 3.0, CQRS, ports and adapters.*

---

## 0. How to read this

The whole document is one idea:

> **Requirements are about the institution. Architecture is how you chose to realise them. Evidence is how you prove you did. Every link in that chain has a name.**

There are **three movements**, and every concept below sits in one of them:

| # | Movement | Question it answers | Concepts |
|---|---|---|---|
| **A** | Decompose the business | *Where does responsibility belong?* | Capabilities → Subdomains → Bounded Contexts → Modules |
| **B** | Implement behaviour | *How does the system behave?* | Use Cases → Use-Case Slices → Vertical Slices → Commands/Queries → Aggregates/Read Models → Events/Projections → Policies → Process Managers |
| **C** | Connect things safely | *How do meaningful parts talk over unreliable machinery?* | Published Messages → Unit of Work → Outbox/Inbox; Ports → Adapters |

**Quality attributes cut across all three.** They add no boxes; they explain why these boxes and not others.

**Verification sits underneath all three.** Architecture is *intended* to satisfy a requirement. Only evidence closes it.

Sections 1–2 cover requirements. Sections 3–17 walk the three movements. Sections 18–19 cover rationale and proof. Section 20 is a single end-to-end trace, 21 the master diagram, 23 a repeatable checklist. The appendices record provenance, what is *not* canonical, and the open questions this model deliberately leaves for you.

---

## 1. The six kinds of requirement driver

Capabilities and quality attributes are **architectural drivers**. They are not the requirements model, and treating them as the whole of it loses four things that shape the architecture just as hard.

```text
                    BUSINESS / REQUIREMENTS
                              │
   ┌──────────┬──────────┬────┴─────┬───────────┬──────────┐
   │          │          │          │           │          │
CAPABIL-   USE CASES  BUSINESS   BUSINESS    QUALITY   CONSTRAINTS
 ITIES                RULES /    PROCESSES  ATTRIBUTE
                     INVARIANTS              SCENARIOS
   │          │          │          │           │          │
   ▼          ▼          ▼          ▼           ▼          ▼
 domain   application  domain    coordination  arch.    restrict
discovery  behaviour   model /   (possibly     decisions the option
   /       (slices)    aggregates  process     / tactics   space
decompo-               / policies  managers
 sition
```

| Driver | Question it answers | Registry example | Where it lands |
|---|---|---|---|
| **Capability** | What must the organisation be able to do? | Incorporation | Subdomains, contexts, modules |
| **Use case** | How does an actor achieve a goal? | Submit an incorporation application | Use-case slices → vertical slices |
| **Business rule / invariant** | What must always be true? | A submitted application has ≥1 director | Aggregates, policies, or rule data |
| **Business process** | What sequence must occur across time? | Incorporation lifecycle (BPMN) | Process managers |
| **Quality attribute scenario** | How well, measurably? | p95 search < 300 ms at 500 concurrent | Architectural decisions, ADRs |
| **Constraint** | What is non-negotiable and given? | Government cloud; Clojure; 20-year statutory retention | Removes options before you choose |

**Constraints deserve particular attention** because they are the only driver that *subtracts*. Everything else opens options; constraints close them. Record them first — "we must use the national payment gateway" eliminates half your Payments design space, and discovering that in month six is expensive.

---

## 2. Quality attribute scenarios — all six parts

A quality attribute scenario has six parts. The one most often dropped, **Environment**, is the one that makes performance and availability requirements testable at all.

```text
QA-PERF-03
  Source:       Public user (unauthenticated)
  Stimulus:     Searches the register by company number
  Environment:  Normal operation, 500 concurrent requests,
                register of 1.2M companies
  Artefact:     Public Search API + public_company_index read model
  Response:     Matching company summary returned
  Measure:      p95 < 300 ms, p99 < 800 ms, zero 5xx
```

Without `Environment`, "p95 < 300 ms" means nothing. At what load? In normal operation or degraded mode? During a projection rebuild?

Write a second scenario for the degraded case, because on a registry it's the interesting one:

```text
QA-PERF-04
  Source:       Public user
  Stimulus:     Searches the register by company number
  Environment:  During a full projection rebuild of
                public_company_index
  Artefact:     Public Search API
  Response:     Results served from the previous (green) index
  Measure:      p95 < 300 ms maintained; index staleness ≤ 15 min,
                and staleness displayed to the user
```

That second scenario is what forces blue/green projections into the design. The first one alone doesn't.

> **Terminology.** ISO/IEC 25010:2023 defines nine product quality characteristics: Functional Suitability, Performance Efficiency, Compatibility, Interaction Capability, Reliability, Security, Maintainability, Flexibility, Safety. The 2023 revision replaced *Usability* with *Interaction Capability* and *Portability* with *Flexibility*, added *Safety*, and moved *scalability* under Flexibility. Several attributes you will use daily — auditability, traceability, correctness — are not top-level ISO characteristics. Non-repudiation is, as a sub-characteristic of Security (see §19).

---

# MOVEMENT A — Decompose the business

---

## 3. Capability → Subdomain

A capability tells you *where to investigate*, not what to build.

```text
Capability: Incorporate a company
        ↓ domain investigation
application · applicants · proposed officers · proposed name ·
constitution · name availability rules · examination criteria ·
examiner assignment · approval/rejection · incorporation decision ·
register entry · statutory fees
        ↓ what distinct kinds of knowledge are these?
Subdomains
```

```text
Corporate Registry Domain
├── Incorporation          (core)
├── Company Register       (core)
├── Examination            (core)
├── Filing                 (core)
├── Public Disclosure      (supporting)
├── Payments               (generic)
└── Identity & Authority   (generic)
```

### Many-to-many, in both directions

```text
Capability "Incorporate a company" touches:
    Incorporation · Payments · Examination · Company Register ·
    Public Disclosure · Identity

Subdomain "Payments" supports:
    Incorporation · Filing · Compliance
```

### Classification changes your investment

| Type | Meaning | Registry example | Action |
|---|---|---|---|
| **Core** | Statutory reason you exist; no vendor can supply it | Company Register, Incorporation, Examination | Best people, richest model, full DDD, event sourcing |
| **Supporting** | Necessary and specific to you, not differentiating | Public Disclosure formatting | Build simply; don't over-model |
| **Generic** | Solved problem | Payments, Identity, Email, PDF generation | Buy; wrap behind a port |

> **Registry consequence:** the statutory register is core because it *is* the legal instrument — an error in it has legal effect on third parties. Payments is generic; use the national gateway behind a `PaymentGateway` port. Spending equal modelling effort on both is a resourcing error, and the most common one.

---

## 4. Subdomain → Bounded Context

**The most important transition in the document.** A subdomain is *discovered*. A bounded context is *decided*.

```text
Problem space                       Solution space
(the business as it is)             (models we choose to build)

Capability
    ↓
Subdomain
    ↓
═════════════ decision boundary ═════════════
    ↓
Bounded Context
    ↓
Module
```

### The registry decomposition

```text
┌─────────────────────────┐
│  Incorporation BC       │   Application
│                         │   Applicant
│                         │   ProposedDirector
│                         │   ProposedName
│                         │   Constitution
│                         │   Submission
└───────────┬─────────────┘
            │  ApplicationSubmitted   (domain event)
            │  IncorporationSubmitted (integration event)
            ▼
┌─────────────────────────┐
│  Examination BC         │   Examination
│                         │   Examiner
│                         │   ExaminationCriterion
│                         │   ExaminationDecision
└───────────┬─────────────┘
            │  ExaminationApproved (integration event)
            ▼
┌─────────────────────────┐
│  Company Register BC    │   Company
│                         │   Director
│                         │   RegisteredOffice
│                         │   CompanyStatus
└─────────────────────────┘
```

Incorporation's use cases are:

```text
Start application · Add director · Remove director ·
Change registered office · Attach constitution ·
Submit application · Withdraw application · Get application
```

Note what is *absent*: `Examine`, `Approve`, `Reject`. Those belong to Examination BC. Incorporation learns the outcome by consuming `ExaminationApproved` / `ExaminationRejected`.

**Why examination is separate:** an examiner is a different actor with a different goal; examination criteria change by statutory instrument independently of application forms; examination has its own workload, queue and SLA. Three independent reasons to change means three separate contexts, not one.

Resist the pull to draw examination as a separate context on the map and then quietly let Incorporation own `Examination` and `IncorporationDecision` in code. That collapse is exactly what bounded contexts exist to prevent, and it happens by drift rather than by decision.

### The word that proves the point

`Director`:

| Context | What it means there |
|---|---|
| Incorporation | `ProposedDirector` — a claim, not yet legally real, no appointment date, has consent-to-act evidence |
| Examination | A subject of disqualification and identity checks; a risk item |
| Company Register | A legally appointed officer with appointment and cessation dates |
| Public Disclosure | A redacted public entry (residential address suppressed) |

One word. Four models. Any attempt at one canonical `Director` class produces a bag of nullable fields and boolean flags — and that bag *is* the failure mode.

### The mapping is not 1:1

| Case | When | Example |
|---|---|---|
| 1 subdomain → 1 context | The desirable default | Examination |
| 1 subdomain → several contexts | Distinct models inside one knowledge area | Filing → *Filing Submission* + *Filing Compliance* |
| Several subdomains → 1 context | Small, tightly related, one team | Identity + Authority |
| Legacy forces it | A context wraps something you can't change | Legacy register behind an ACL |

---

## 5. The context map

Contexts relate. Name every relationship explicitly, on one page, in the repo.

| Pattern | Meaning | Registry example |
|---|---|---|
| **Customer / Supplier** | Downstream has a say in upstream's roadmap | Public Disclosure ← Company Register |
| **Conformist** | Downstream accepts upstream's model as-is | Filing conforms to register company identifiers |
| **Anticorruption Layer** | Downstream translates to protect its model | New context wrapping the legacy register |
| **Open Host Service** | Upstream publishes a stable protocol for many consumers | Company Register's published message contract |
| **Published Language** | Shared, versioned interchange format | Your integration message schema registry |
| **Shared Kernel** | Two contexts share a small model — high coupling | `Money`, `CompanyNumber` value objects. Use sparingly. |
| **Separate Ways** | No integration; duplicate instead | Internal examiner notes vs public register |

> This is the highest-value diagram you will own. It is also the one people argue about productively, which is the point.

---

## 6. Bounded Context → Module

**Default: one bounded context → one module.** Deviate only with a written reason.

```text
modules/
├── incorporation/
├── examination/
├── company-register/
├── filing/
├── public-disclosure/
├── payments/
└── identity/
```

A directory named `modules/` proves nothing. Four constraints do the work. They are choices this architecture adopts, not definitions of the term — each deserves an ADR.

**C1 — No module reaches into another module's internals.**

```text
✗  incorporation/ imports company-register/domain/Company
✓  incorporation/ consumes a published integration message
```

**C2 — Each module owns its schema. No cross-module joins.**

```text
Postgres
├── schema incorporation      ← only modules/incorporation
├── schema examination        ← only modules/examination
└── schema company_register   ← only modules/company-register
```

*Why I state this one strongly:* plenty of systems called modular monoliths share a schema, so this is a constraint rather than a definition. But the shared schema is the mechanism by which nearly all of them decayed. A cross-module join is invisible to code review, invisible to the module system, and creates coupling that only surfaces when someone changes a column. Of the four this is the one worth fighting for — and the ADR should acknowledge its cost: you lose easy reporting joins and will need a dedicated reporting projection instead.

**C3 — Boundaries enforced by tooling, not intentions.**

This determines whether C1 and C2 survive contact with a deadline. In Clojure: namespace-dependency checks in CI, or a `:module` metadata convention with a custom linter. If a boundary can be broken silently on a Friday afternoon, it will be.

**C4 — Each module extractable as a service without a rewrite.**

A diagnostic, not a plan. You should probably never extract these services. But if extracting Filing would take three months, C1 or C2 has already been violated somewhere you haven't looked.

---

# MOVEMENT B — Implement behaviour

---

## 7. Use Case → Use-Case Slice → Vertical Slice

"Slice" means two different things in this stack, and conflating them causes real damage.

| | **Use-case slice** (Use-Case 3.0) | **Vertical slice** (Vertical Slice Architecture) |
|---|---|---|
| Origin | Jacobson, Spence, de Mendonca, *Use-Case 3.0* (Dec 2024); concept introduced in Use-Case 2.0 (2011) | Jimmy Bogard, ~2018 |
| What it is | One of the ways of using a system to achieve a goal, sliced thin enough to deliver | A folder of code implementing one operation through all layers |
| Unit of | Requirements, planning, acceptance testing | Code organisation |
| Delivers | Stakeholder value | A command or query handler |
| Can be non-functional | **Yes** — UC3.0 explicitly allows slices that exist to prove an NFR | No |
| Can cross bounded contexts | **Yes** | **No** — a vertical slice lives inside one module |
| Implemented by | Work items (user stories, features, tasks) | It *is* the implementation |

### The chain

```text
Use Case
   "Incorporate a company"  (actor: Applicant; goal: have a company exist)
        ↓  sliced by ways-of-achieving-the-goal
Use-Case Slices
   Slice 1: Incorporate with a standard constitution
   Slice 2: Incorporate with a bespoke constitution
   Slice 3: Incorporate with a corporate director
   Slice 4: Handle name-unavailable
   Slice 5: Handle examination rejection
   Slice N: Prove p95 search latency under load   ← non-functional slice
        ↓  each slice needs work items, which become code
Implementation Vertical Slices  (per module)
   incorporation/submit-application/
   incorporation/attach-constitution/
   examination/record-decision/
   company-register/register-company/
        ↓
Commands / Queries
```

### Where the two diverge

This is the part that matters:

```text
Use-case slice 1: "Incorporate with a standard constitution"
        │
        │  spans FOUR modules
        │
        ├──► incorporation/     submit-application/   (vertical slice)
        ├──► payments/          take-payment/         (vertical slice)
        ├──► examination/       record-decision/      (vertical slice)
        ├──► company-register/  register-company/     (vertical slice)
        │
        └──► incorporation-process-manager            (coordination)
```

**One use-case slice → N vertical slices across M modules, plus possibly a process manager step.** The use-case slice is the *acceptance test boundary*; the vertical slice is the *code boundary*. They are different boundaries and should be allowed to differ.

Forcing them 1:1 does one of two damaging things: it fragments the user's goal into meaningless increments, or it smears one code folder across module boundaries. Keep them separate and both stay coherent.

```text
Use-case slice       → one acceptance test, one demo, one sign-off
Vertical slice       → one folder, one PR, one unit/integration test
Process manager step → the glue, tested separately
```

For public-sector governance, this answers "where is the specification?" — the use-case slice, with its test cases, *is* the specification, signed slice by slice ahead of build.

---

## 8. Event Modeling finds the slices

Event Modeling is the bridge between the use-case model and the code. It lays time left-to-right and produces four repeating patterns, each mapping onto a slice type:

| Event Modeling pattern | Shape | Becomes |
|---|---|---|
| **State Change** | UI/trigger → Command → Event | A command vertical slice |
| **State View** | Events → Read Model → UI | A query vertical slice + projection |
| **Automation** | Read Model → Command (a "todo list") | A policy or process manager step |
| **Translation** | External system ↔ Event | An adapter + integration message |

```text
Event Modeling board
        │  each swim lane =
        ▼
one vertical slice
        │
        ▼
one folder in one module
```

**Why it matters for the chain:** Event Modeling gives you commands, events and read models *before* you decide aggregate boundaries. Aggregates are then discovered by asking which events must be consistent together, rather than guessed up front and retrofitted. That ordering is much safer.

> Event Modeling is a community technique with no standards body behind it, and its "automation" pattern deliberately blurs policy and process manager. Use it for discovery; use §14 for the distinction.

---

## 9. CQRS splits the slices

```text
Vertical Slice
   │
   ├── changes state ──► Command slice
   └── observes state ─► Query slice
```

```text
POST /applications/APP-123/submit      GET /applications/APP-123
           ↓                                     ↓
   SubmitApplication command             GetApplication query
           ↓                                     ↓
   handler (shell)                       handler
           ↓                                     ↓
   pure decide()                         read model
           ↓                                     ↓
   domain events                         response
```

### The cost, decided per screen

**Eventual consistency on the read side.** This is a business conversation, not a technical one:

> After an applicant submits, does their dashboard immediately show "Submitted"?

Pick one *per screen* and write it down:

```text
(a) Read your own writes from the write side for that one screen
(b) Block the response until the projection checkpoint passes the event
(c) Return the expected state optimistically from the command response
(d) Show "Submitting…" and poll
```

For a registry, `(b)` on the applicant's own dashboard and `(d)` on public search is usually the right split. Discovering this in production is not.

---

## 10. Business rules have three destinations

Choosing the wrong one is expensive.

```text
Business Rule
     │
     ├──► INVARIANT in an aggregate
     │    "must be atomically true at commit"
     │    e.g. A submitted application has ≥1 director
     │
     ├──► POLICY or PROCESS MANAGER STEP
     │    "must eventually become true"
     │    e.g. Every approved application eventually
     │         yields a registered company
     │
     └──► DATA, evaluated against a rule store
          "changes by statutory instrument, not by deployment"
          e.g. Examination criteria; prohibited-name lists;
               fee schedules
```

**The third destination is the one architects forget.** For a corporate registry a large fraction of your "rules" are legislation. Legislation changes on a schedule you don't control, often mid-build, and often with an effective date rather than a deployment date.

```text
✗  if (name.contains("Bank") && !hasCentralBankConsent) reject()
      → a statutory instrument means a code change and a release

✓  ProhibitedNameRule { pattern, consenting-authority,
                        effective-from, effective-to }
      → versioned, effective-dated data,
        with the rule version recorded on the decision event
```

That last clause matters enormously: **record which rule version was applied, on the event.** Otherwise you cannot reconstruct *why* a 2024 decision was correct under 2024 law — which is exactly what an appeal or judicial review will ask.

---

## 11. Aggregates — the consistency boundary

> An aggregate is a **consistency boundary**, not a data structure. It is the set of things that must be transactionally correct together, with a single root as the only entry point.

```text
Use Case → Vertical Slice → Command
                              ↓
                     Handler (imperative shell)
                              ↓
                     pure decide(state, command)
                              ↓
                     Domain Events
```

### Choosing the boundary

Ask: **what must be true at the instant the transaction commits?**

```text
✓ Inside — atomic:
    "A submitted application has at least one director,
     a registered office, and a completed declaration."

✗ Outside — eventual:
    "Every approved application yields a registered company."
    → that's a process (§14)
```

### Keep them small

```text
✗  Company aggregate holding every filing since 1963
      → 40,000 events to load before changing an address
      → every filing contends on one stream

✓  Company aggregate       (identity, status, officers, office)
   Filing aggregate        (one per filing, references company by ID)
   Examination aggregate   (one per examination)
```

Reference other aggregates **by identity, never by object reference**. One aggregate per transaction is the default; needing two usually means the boundary is wrong, or you need a process manager.

---

## 12. Event sourcing with a genuinely pure core

Under functional core / imperative shell, the purity of `decide` and `evolve` is the whole point of the style. Nothing non-deterministic may enter them — including clocks and ID generators.

### The shape

```text
IMPERATIVE SHELL (handler)
  │
  ├── Clock port        → now      = 2026-08-21T09:14:22Z
  ├── IdGenerator port  → new-id   = "EXM-2026-04412"
  ├── EventStore port   → history  = [...]
  │
  ▼
FUNCTIONAL CORE (pure, no I/O, no ambient state)
  │
  state  = (evolve history)
  events = (decide state command-with-injected-values)
  │
  ▼
IMPERATIVE SHELL
  └── persist events + outbox messages in ONE transaction (§16)
```

```clojure
;; shell resolves everything non-deterministic, then calls pure code
(let [history (event-store/load store stream-id)
      state   (evolve history)
      events  (decide state
                      {:command/type   :application/submit
                       :application-id application-id
                       :actor-id       actor-id
                       :now            (clock/now clock)   ; a VALUE
                       :new-id         (ids/next id-gen)   ; a VALUE
                       :rule-version   (rules/current-version rules)})]
  (unit-of-work/commit! uow stream-id expected-version events))
```

### Why this is not pedantry

```text
Clock inside the aggregate:
  → decide() returns different events for the same inputs
  → replay is non-deterministic
  → in an event-sourced system that is a CORRECTNESS bug,
    not a testing inconvenience
  → you cannot prove the 2024 decision was correct,
    which defeats the auditability requirement that
    motivated event sourcing in the first place
```

Cockburn's original hexagonal formulation places ports at the boundary between the *application* and external agencies, which supports exactly this: **ports live in the shell, not in the domain.**

### Command vs event

```clojure
;; COMMAND — a request. May be refused.
{:command/type :application/submit
 :data     {:application-id "APP-2026-00123"}
 :metadata {:actor "user-4471"
            :correlation-id "corr-889"
            :causation-id   "cmd-1204"}}

;; EVENT — a fact. Cannot be refused. Cannot be deleted.
{:event/type :application/submitted
 :data     {:application-id "APP-2026-00123"
            :submitted-by   "user-4471"
            :director-count 2
            :rule-version   "SI-2026-14"}
 :metadata {:correlation-id "corr-889"
            :causation-id   "cmd-1204"
            :occurred-at    "2026-08-21T09:14:22Z"}}
```

### Event sourcing sits *underneath* the use case

The requirement never says "use event sourcing." It says "an applicant shall be able to submit an application" and "every legally significant change shall be reconstructable and attributable." Event sourcing is a mechanism you *chose*. That belongs in an ADR (§18), not the requirements register.

### The costs

| Cost | Mitigation |
|---|---|
| Event schema evolution is genuinely hard | Upcasters, additive-only changes, versioned event types, a schema registry |
| Erasure vs an immutable log | See §19 — harder than it first appears |
| Rebuilding projections at volume | Snapshots, checkpoints, blue/green rebuild, validation |
| Steep learning curve | Confine event sourcing to **core** subdomains |

> **Recommendation:** event-source Incorporation, Examination and Company Register. Use plain CRUD for Payments, Identity and Public Disclosure. Uniformity is not a virtue here.

---

## 13. Projections and read models

> Read models are **derived, replaceable data**. Given a complete event history *and* projection code capable of interpreting every historical event version, they can be rebuilt from the event store.

Both conditions are load-bearing. Rebuilding is *possible*, not free. What you still need:

```text
event upcasting / version-tolerant projections
projection checkpoints (so rebuild is resumable)
blue/green rebuild (so reads stay available — QA-PERF-04)
rebuild validation (row counts, spot-check invariants,
                    reconciliation against the write side)
read-model schema migration (the target table still changes)
rebuild duration budget (at 1.2M companies, this is hours)
```

The principle holds — events are authoritative, projections are not — but plan the rebuild as a real operation.

```text
Domain Events → Projection → Read Model → Query
```

```text
ApplicationStarted
DirectorAdded
DirectorAdded
RegisteredOfficeChanged
ApplicationSubmitted
        ↓
ApplicationDetails projection (with checkpoint)
        ↓
application_details table
        ↓
GET /applications/APP-2026-00123
```

---

## 14. Policies and Process Managers

> **If the reaction is part of a remembered sequence, the process manager owns it.**
> **If it's an independent WHEN/IF/THEN with no process memory, a policy owns it.**

Splitting one workflow between the two is how coordination bugs are born. One sequence, one owner.

### Process manager — owns the whole sequence

```text
ApplicationSubmitted
        ↓
IncorporationProcessManager
        ├── state := WAITING_FOR_PAYMENT
        ├── timeout := +14 days
        └── emits RequestPayment            (integration COMMAND)

PaymentConfirmed
        ↓
IncorporationProcessManager
        ├── state := WAITING_FOR_EXAMINATION
        └── emits RequestExamination        (integration COMMAND)

ExaminationApproved
        ↓
IncorporationProcessManager
        ├── state := REGISTERING
        └── emits RegisterCompany           (integration COMMAND)

CompanyRegistered
        ↓
IncorporationProcessManager
        ├── state := COMPLETE
        └── emits CompleteIncorporation
```

### Policy — independent, stateless consequences only

```text
WHEN CompanyRegistered
IF   company type requires it
THEN ScheduleFirstAnnualReturn     (Filing context — unrelated to
                                    the incorporation sequence)

WHEN ExaminationRejected
THEN NotifyApplicant

WHEN RegisteredOfficeChanged
THEN InvalidatePublicDisclosureCache
```

None of these depends on *where we are* in the incorporation process. That's the test.

### Comparison

| | Policy | Process Manager |
|---|---|---|
| State | None | Owns its own state |
| Span | One event | Many events over time |
| Contexts | Usually one | Usually several |
| Timeouts | No | Yes |
| Failure | Retry | Compensate or escalate |
| Owns a sequence | No | **Yes** |

### Compensation — why process managers exist

Examination approved, then registration fails because the name was taken in the interim:

```text
CompanyRegistrationFailed {reason: name-taken}
        ↓
IncorporationProcessManager decides:
        ├── RefundPayment           (compensating action)
        ├── ReopenApplication       (compensating action)
        └── NotifyApplicant         (escalation)
```

You cannot *undo* `ExaminationApproved` — it happened. You issue compensating actions that produce **new** facts. This is the semantic difference between a distributed process and a database transaction, and the thing teams most often get wrong.

### BPMN

```text
BPMN process  ──describes──►  cross-context workflow
                                      │
                                may be implemented by
                                      ▼
                              Process Manager
```

Not every BPMN diagram needs one. Where a process crosses contexts and must survive restarts, the process manager is its runtime form.

> **Terminology.** "Saga" means two incompatible things in industry: Garcia-Molina & Salem's 1987 long-lived transaction with compensations, and (in some frameworks) any stateful event handler. "Process manager" (Hohpe & Woolf, *Enterprise Integration Patterns*) is unambiguous. Use it, and define it in your glossary.

---

# MOVEMENT C — Connect things safely

---

## 15. Published Messages: commands *and* events

Cross-context communication is not only events. Facts and requests are semantically different and must be modelled differently.

```text
INSIDE a bounded context
        Domain Events (internal, rich, private, change freely)
                    │
                    │ translation + publication decision
                    ▼
ACROSS bounded contexts
        PUBLISHED MESSAGES
            ├── Integration COMMAND — directed intent, one recipient
            └── Integration EVENT   — published fact, N subscribers
```

### The difference

```text
Integration Command
  "Payments: take this payment."
  → RequestPayment {incorporation-id, amount, fee-code}
  → ONE intended recipient
  → CAN be refused (PaymentRejected)
  → sender expects an outcome

Integration Event
  "Anyone interested: this payment succeeded."
  → PaymentConfirmed {payment-id, incorporation-id, amount, at}
  → N subscribers; sender doesn't know or care who
  → CANNOT be refused; it already happened
  → sender expects nothing
```

Getting this wrong produces a specific pathology: modelling a request as an event (`PaymentRequested`) and then finding nobody is clearly responsible for handling it — or two consumers both take the payment.

### Domain event ≠ integration event

```text
Incorporation BC

ApplicationApproved
  {application-id, examiner-id, internal-score,
   examiner-notes, risk-flags, draft-revision}
        │  internal — rich, private, changes with the model
        ▼
   translation
        │
        ▼
IncorporationApproved
  {incorporation-id, proposed-name, company-type,
   officers[], registered-office, approved-at, schema-version}
        │  public — minimal, versioned, a CONTRACT
        ▼
Company Register BC
```

| Domain Event | Integration Message |
|---|---|
| Internal to one context | Public across contexts |
| Model-shaped | Contract-shaped |
| Changes freely | Versioned; breaking changes are themselves events |
| Named in that context's language | Named in the published language |
| Not a contract | **Is** a contract |

If Company Register subscribes directly to Incorporation's internal `ApplicationApproved`, renaming a field inside Incorporation breaks Company Register. You have re-coupled the modules through the bus and undone §6. **This is the most common way a modular monolith quietly becomes a distributed ball of mud.**

Both message kinds travel the same road:

```text
Published Message → Outbox → transport → Inbox → consumer
```

---

## 16. The unit of work is an architectural primitive

Exposing `EventStore` and `Outbox` as two independent ports invites exactly the dual-write bug the outbox exists to prevent:

```text
✗  (event-store/append! store events)
   (outbox/insert! outbox messages)     ← different transaction. Broken.
```

There is no ordering of two independent systems that is safe. Make the transaction boundary explicit and first-class.

```text
Application Handler
        │
        ▼
┌─────────────────────────────────────────┐
│  UNIT OF WORK  (one local transaction)  │
│                                         │
│  ├── record inbox entry (if reacting)   │
│  ├── append domain events               │
│  ├── update process manager state       │
│  └── enqueue outgoing published messages│
│                                         │
│  COMMIT  ── atomic, or nothing happens  │
└─────────────────────────────────────────┘
        │
        ▼  (separately, asynchronously)
   Outbox relay → transport → consumer's Inbox
```

### For a process manager, all three in one transaction

```text
ONE LOCAL TRANSACTION
├── record incoming message in inbox   (dedupe)
├── update PM state (WAITING_FOR_PAYMENT → WAITING_FOR_EXAMINATION)
└── enqueue RequestExamination in outbox
COMMIT
```

If any of those can commit independently, the process manager will eventually lose a step or duplicate one under failure. That is not a theoretical risk; it is the default outcome.

### The architectural consequence

**The event store and the outbox must live in the same transactional resource.** That is a real constraint with real cost, and it deserves an explicit ADR:

```text
ADR-031: Event store and outbox share one PostgreSQL database

Consequence: we cannot independently swap the event store for a
dedicated event-store product without either giving up atomic
outbox writes or introducing a distributed transaction. We accept
this. Reliability of inter-context delivery outweighs event-store
optionality.
```

Say it out loud in the ADR. Otherwise someone will "improve" the event store in year three and silently reintroduce dual writes.

### Why the inbox is needed too

The outbox relay guarantees **at-least-once** delivery. Duplicates are normal, not exceptional. The inbox makes idempotency cheap:

```text
consumer transaction:
├── has this message-id been processed? → yes: skip, commit
├── no: process it
├── record message-id in inbox
└── COMMIT
```

> Outbox and inbox are **reliability mechanisms, not business concepts.** They must never appear in a requirement, a domain model, or a conversation with a domain expert.

---

## 17. Ports and Adapters

```text
                    DRIVING SIDE (primary)
   HTTP ──adapter──► ┐
   CLI  ──adapter──► ├──► Command / Query Ports
   Cron ──adapter──► │
   Msgs ──adapter──► ┘
                              │
                              ▼
                    ┌───────────────────┐
                    │ IMPERATIVE SHELL  │
                    │  handlers,        │
                    │  process managers │
                    │                   │
                    │  ┌─────────────┐  │
                    │  │ FUNCTIONAL  │  │
                    │  │    CORE     │  │  ← NO ports in here.
                    │  │ evolve /    │  │    Values only.
                    │  │ decide      │  │
                    │  └─────────────┘  │
                    └─────────┬─────────┘
                              │
        ┌─────────────────────┼──────────────┬────────────┐
        ▼                     ▼              ▼            ▼
  ┌───────────────────────────────┐      Clock        IdGenerator
  │   UNIT OF WORK port           │       Port           Port
  │   (event store + outbox +     │         │              │
  │    inbox + PM state, atomic)  │    System clock     UUIDv7
  └───────────────┬───────────────┘      Adapter        Adapter
                  │
             PostgreSQL adapter
                    DRIVEN SIDE (secondary)
```

Two things that diagram enforces:

1. `Clock` and `IdGenerator` ports live in the **shell**, not the domain. Their *values* are passed into `decide`.
2. Event store and outbox sit behind **one** unit-of-work port, not two independent ones.

### The dependency rule

```text
adapters ──depend on──► ports ◄──defined by── shell ──uses──► pure core

The functional core depends on NOTHING. Not even ports.
```

If a domain namespace imports a Postgres driver — or a clock — the architecture has already failed. Make it a CI check.

---

# RATIONALE AND PROOF

---

## 18. Quality attribute → decision → mechanism → evidence

**Architecture does not satisfy a requirement. Architecture is *intended* to satisfy it. Verification produces evidence. Evidence closes it.**

For a statutory register that distinction is not academic — it's the difference between a design claim and an assurance artefact you can put in front of an auditor.

### The full chain

```text
Quality Attribute Scenario
        ↓
Architectural concern
        ↓
Options considered
        ↓
Architectural DECISION            ← judgement lives here
        ↓
ADR
        ↓
Mechanism (implemented)
        ↓
VERIFICATION method               ← how we will prove it
        ↓
EVIDENCE (measurement / test result / audit)
        ↓
Requirement VERIFIED              ← only now is it closed
```

A quality attribute never *implies* a mechanism. Auditability can be met by an append-only audit table, temporal tables, CDC into an immutable log, or event sourcing. The ADR is the only place the word "therefore" is allowed between a requirement and a technology.

### Worked: QA-AUD-01

```text
QA-AUD-01
  "Every legally significant state transition shall be
   reconstructable and attributable to an actor, an authority,
   and the rule version in force at the time."

        ↓ concern
Historical truth / auditability

        ↓ options
(a) Append-only audit table alongside mutable state
(b) Temporal / bitemporal tables
(c) CDC into an immutable log
(d) Event sourcing

        ↓ decision
(d), for core registry contexts only. The audit record and the
state must be the SAME artefact. Options (a) and (c) allow them
to diverge, which for a statutory register is an unacceptable
class of defect. Rejected for supporting/generic subdomains.

        ↓ ADR-014

        ↓ mechanism
Event store + domain events + correlation/causation +
actor + authority + rule-version on every event

        ↓ VERIFICATION
VER-AUD-01: Automated audit-reconstruction test. Sample 1,000
random companies; reconstruct full directorship history from
events; compare against an independently-held register extract.
Plus annual external audit of a statutory sample.

        ↓ EVIDENCE
EVD-AUD-01: Test run 2026-08-14. 1,000/1,000 reconstructed.
0 discrepancies. External audit report ref XR-2026-118.

        ↓
QA-AUD-01 VERIFIED
```

### Worked: QA-PERF-03

```text
QA-PERF-03 (p95 < 300 ms at 500 concurrent)
        ↓ decision
CQRS + dedicated public_company_index projection  (ADR-021)
        ↓ mechanism
Denormalised read model, async projection, checkpointed
        ↓ VERIFICATION
VER-PERF-03: Load test, 500 concurrent, 1.2M-company dataset,
             production-equivalent infrastructure
        ↓ EVIDENCE
EVD-PERF-03: 2026-08-19. p95 = 187 ms. p99 = 402 ms. 0 5xx.
        ↓
QA-PERF-03 VERIFIED
```

**The arrow that must never appear** in a requirements register is `mechanism → satisfied`. It is always `mechanism → verification → evidence → verified`.

---

## 19. Two claims that need heavy qualification

### Non-repudiation

An event log gives you **audit history**. Non-repudiation (ISO 25010 Security sub-characteristic) means the actor cannot credibly *deny* having performed the action. An append-only table is append-only by convention and database permissions — a DBA, a bug, or a restore-from-backup can alter it, and the log itself cannot prove otherwise.

```text
Audit history        ← event sourcing gives you this
Tamper-evidence      ← needs a hash chain over events
Non-repudiation      ← needs cryptographic signing of the actor's
                       intent, bound to a credential only that
                       actor controls, plus a trusted timestamp
```

Where a false filing has legal consequences, decide deliberately which of the three you need, per event type. A reasonable split:

```text
Ordinary reads / internal state    → audit history
All registry-affecting events      → tamper-evident hash chain
Statutory declarations by officers → signed, with qualified
                                     e-signature under eIDAS
                                     (if operating in the EU)
```

Write this as its own QA scenario and its own ADR. Don't let it ride on the event-sourcing decision.

### GDPR erasure

Three separate problems, and the first probably matters most.

**1. The right to erasure may not apply to the statutory register at all.** GDPR Article 17(3) provides exemptions, including where processing is necessary for compliance with a legal obligation or for a task carried out in the public interest. A statutory corporate register is a strong candidate. If so, the architectural problem largely evaporates for the register itself — while remaining live for *ancillary* data: draft applications, examiner notes, contact details, abandoned submissions.

**2. Whether crypto-shredding counts as erasure is legally unsettled.** The argument is that encrypted data whose key is destroyed is effectively anonymised. Regulators have been cautious about treating encrypted-and-key-destroyed data as outside the scope of personal data, and the analysis turns on the "means reasonably likely to be used" test. Treat it as a defensible position, not a settled one.

**3. It has real engineering cost.** Per-subject keys, key custody, key rotation, replay behaviour when a key is gone (projections must handle a permanent null-out gracefully), and the fact that a shredded event breaks reconstructability — which conflicts directly with QA-AUD-01.

```text
The register itself:  likely exempt under Art 17(3).
                      CONFIRM WITH COUNSEL — a legal
                      determination, not an architectural one.

Ancillary data:       segregate it. Keep erasable personal data
                      OUT of the event streams that must be
                      permanent. Reference it by ID from a
                      separately-governed store.

Crypto-shredding:     a fallback, with an ADR that states the
                      legal uncertainty explicitly.
```

**The architectural move that actually helps** is the second one: design event streams so erasable personal data lives outside them from day one. Retrofitting that is very hard.

> I am not a lawyer and this is not legal advice. These are the questions to put to counsel, not the answers.

---

## 20. One thread, end to end

```text
①  DRIVERS
    Capability:  Incorporation
    Use case:    "Incorporate a company"
    UC slice:    "Incorporate with a standard constitution"
    Rule:        A submitted application has ≥1 director
    Process:     Incorporation lifecycle (BPMN-IN-01)
    QA:          QA-AUD-01, QA-PERF-03
    Constraint:  National payment gateway mandatory

②  SUBDOMAINS — Incorporation (core), Examination (core),
    Company Register (core), Payments (generic)

③  BOUNDED CONTEXTS — four, per §4

④  MODULES — one per context, one schema each

⑤  USE-CASE SLICE spans four modules → four vertical slices
    + one process manager

⑥  REQUEST
    POST /applications/APP-2026-00123/submit
         ↓ HTTP adapter (driving)
    SubmitApplication command {correlation-id, actor}

⑦  HANDLER (imperative shell)
    Clock port      → now
    EventStore port → history
    history → evolve → state
    decide(state, command + now + rule-version)   ← PURE

⑧  INVARIANTS CHECKED IN decide()
    ✓ exists  ✓ DRAFT  ✓ ≥1 director
    ✓ office supplied  ✓ constitution attached
    → permitted

⑨  DOMAIN EVENT
    ApplicationSubmitted {application-id, submitted-by,
                          director-count, rule-version}

⑩  ONE UNIT OF WORK
    ├── append ApplicationSubmitted
    ├── initialise IncorporationProcessManager
    │   (state = WAITING_FOR_PAYMENT, timeout +14d)
    └── enqueue RequestPayment  (integration COMMAND)
    COMMIT

⑪  RELAY (async)
    outbox → transport → Payments context inbox

⑫  PROJECTION (async)
    ApplicationSubmitted → application_details.status = SUBMITTED

⑬  THE PROCESS MANAGER DRIVES THE SEQUENCE
    PaymentConfirmed    → PM → RequestExamination
    ExaminationApproved → PM → RegisterCompany
    CompanyRegistered   → PM → CompleteIncorporation

⑭  INDEPENDENT POLICY (not part of the sequence)
    WHEN CompanyRegistered THEN ScheduleFirstAnnualReturn

⑮  VERIFICATION
    VER-AUD-01 audit-reconstruction test  → EVD-AUD-01
    VER-PERF-03 load test                 → EVD-PERF-03
    UC-slice acceptance test              → sign-off

⑯  Only now: QA-AUD-01 VERIFIED, QA-PERF-03 VERIFIED,
    use-case slice ACCEPTED
```

---

## 21. The master model

```text
                       BUSINESS / REQUIREMENTS
                                 │
   ┌───────────┬─────────┬───────┴────┬──────────┬────────────┐
   │           │         │            │          │            │
CAPABIL-   USE CASES  BUSINESS    BUSINESS   QUALITY    CONSTRAINTS
 ITIES                 RULES /    PROCESSES  ATTRIBUTE       │
   │           │      INVARIANTS      │      SCENARIOS       │
   ▼           │          │           │          │           │
SUBDOMAINS     │          │           │          ▼           │
   │           │          │           │    CONCERN → OPTIONS ◄┘
   ▼           │          │           │          │
BOUNDED        │          │           │          ▼
CONTEXTS       │          │           │        DECISION
   │           │          │           │          │
   ▼           │          │           │          ▼
MODULES        │          │           │         ADR
               ▼          │           │          │
        USE-CASE SLICES   │           │          │
               │          │           │          │
               ▼          │           │          │
       VERTICAL SLICES    │           │          │
               │          │           │          │
        ┌──────┴─────┐    │           │          │
        ▼            ▼    │           │          │
    COMMANDS     QUERIES  │           │          │
        │            │    │           │          │
        ▼            ▼    │           │          │
   AGGREGATES  READ MODELS│           │          │
        ▲            ▲    │           │          │
        └────────────┼────┘           │          │
                     │                │          │
                PROJECTIONS           │          │
                     ▲                │          │
                     │                │          │
              DOMAIN EVENTS           │          │
                     │                │          │
        ┌────────────┴──────────┐     │          │
        ▼                       ▼     ▼          │
   POLICY (stateless)    PROCESS MANAGER         │
        │                       │                │
        └───────────┬───────────┘                │
                    ▼                            │
    ══════ BOUNDED CONTEXT BOUNDARY ═════════════│═══
                    │                            │
            PUBLISHED MESSAGES                   │
            ┌───────┴────────┐                   │
            ▼                ▼                   │
     INTEGRATION       INTEGRATION               │
       COMMAND            EVENT                  │
            └───────┬────────┘                   │
                    ▼                            │
        ┌───────────────────────┐                │
        │  UNIT OF WORK         │                │
        │  events + outbox +    │                │
        │  inbox + PM state     │                │
        └───────────┬───────────┘                │
                    ▼                            │
        OUTBOX → transport → INBOX               │
                    ▼                            │
          OTHER BOUNDED CONTEXT                  │
                                                 │
      PORTS & ADAPTERS ◄─── mechanisms ──────────┘
                    │
                    ▼
        VERIFICATION → EVIDENCE → VERIFIED
```

### Summary

```text
WHY / WHAT
  Capabilities · Use Cases · Business Rules ·
  Business Processes · QA Scenarios · Constraints

DOMAIN        Capabilities → Subdomains → Bounded Contexts
STRUCTURE     Bounded Contexts → Modules
BEHAVIOUR     Use Cases → Use-Case Slices → Vertical Slices
              → Commands/Queries → Aggregates/Read Models
              → Domain Events/Projections
COORDINATION  Domain Events → Policies (stateless)
                            → Process Managers (own sequences)
BOUNDARIES    Published Messages (commands + events)
              → Unit of Work → Outbox/Inbox
ISOLATION     Shell → Ports → Adapters; pure core has none
RATIONALE     QA → concern → options → decision → ADR → mechanism
PROOF         → verification → evidence → VERIFIED
```

---

## 22. Traps

**T1 — Requirements that specify mechanisms.**
```text
✗ REQ-123: The registry shall use event sourcing.
✓ The registry shall retain an authoritative history of legally
  significant changes, attributable to actor, authority and
  rule version.
```

**T2 — Treating a design as proof.** §18. Only evidence closes a requirement.

**T3 — Modules sharing a schema.** §6/C2. The one that kills projects.

**T4 — Conflating the two kinds of slice.** §7. Use-case slice = user goal increment, may span contexts. Vertical slice = code in one module.

**T5 — A clock or ID generator inside the pure core.** §12. In an event-sourced system that's a correctness bug.

**T6 — Splitting workflow ownership between policy and process manager.** §14.

**T7 — Modelling a request as an event.** §15. `PaymentRequested` as an event leaves nobody clearly responsible.

**T8 — Two writes where one transaction is needed.** §16.

**T9 — Event sourcing everything.** Payments and Identity don't need it.

**T10 — Anaemic aggregates.** If the handler validates and then calls `application.setStatus(SUBMITTED)`, that's CRUD with ceremony. The decision belongs in `decide`.

**T11 — Legislation hard-coded as `if` statements.** §10. Rules that change by statutory instrument belong in effective-dated data, with the version recorded on the event.

**T12 — Contexts named after technical layers.** `modules/api/`, `modules/services/` is a layered monolith with confusing folder names.

**T13 — Unmanaged eventual consistency in the UI.** §9. Decide per screen, in advance.

**T14 — Personal data baked into permanent event streams.** §19. Very hard to retrofit.

---

## 23. Checklist: adding a capability

```text
□  1. Write the capability in business language
□  2. Write its use cases (actor + goal)
□  3. Slice each use case (Use-Case 3.0), including any
      non-functional slices
□  4. Write the business rules; decide destination for each:
      invariant / policy / effective-dated data
□  5. Identify the business processes (BPMN if cross-context)
□  6. Write QA scenarios — all SIX parts including Environment
□  7. Record the constraints (they subtract options)
□  8. Investigate the domain; identify subdomains
□  9. Classify: core / supporting / generic
□ 10. Decide bounded contexts. Does any word mean two things?
      → that's two contexts
□ 11. Update the context map; name the relationship pattern
□ 12. Decide modules (default 1:1); allocate the schema
□ 13. Event-model it: state change / state view /
      automation / translation
□ 14. Map to vertical slices per module
□ 15. Split commands and queries; decide read-consistency
      strategy per screen
□ 16. For each command: which aggregate? which invariant?
□ 17. Name domain events (past tense, business language)
□ 18. Reactions: stateless → policy; part of a sequence
      → process manager step
□ 19. Crossing a context? → published message. Command or
      event? Version the contract.
□ 20. Confirm all state + messages commit in ONE unit of work
□ 21. Which ports does the shell need? The core needs none.
□ 22. Which QAs drove the non-obvious choices? → ADR first
□ 23. Define VERIFICATION for each QA, and where evidence
      will be recorded
□ 24. Add the CI rule enforcing the new boundary
```

---

## Appendix A — Provenance

Useful when writing ADRs; citing the source shortens arguments.

| Term | Source |
|---|---|
| Bounded Context, Subdomain, Aggregate, Ubiquitous Language, Context Map, ACL | Eric Evans, *Domain-Driven Design* (2003) |
| Core / Supporting / Generic subdomains | Evans (2003); elaborated in Vaughn Vernon, *Implementing DDD* (2013) |
| Small aggregates, reference by identity | Vernon, *Effective Aggregate Design* (2011) |
| Use-Case 2.0 and the use-case slice | Jacobson, Spence & Bittner (2011) |
| Use-Case 3.0 | Jacobson, Spence & de Mendonca (December 2024) |
| Use-Case Foundation (nine principles) | Jacobson & Cockburn (2024) |
| Vertical Slice Architecture | Jimmy Bogard (~2018) |
| Event Modeling | Adam Dymitruk (~2018) |
| CQS | Bertrand Meyer (1988) |
| CQRS | Greg Young (~2010) |
| Event Sourcing | Greg Young; described by Martin Fowler (2005) |
| Functional Core / Imperative Shell | Gary Bernhardt (2012) |
| Ports and Adapters (Hexagonal) | Alistair Cockburn (2005) |
| Process Manager | Hohpe & Woolf, *Enterprise Integration Patterns* (2003) |
| Saga (long-lived transaction) | Garcia-Molina & Salem (1987) |
| Transactional Outbox | Catalogued by Chris Richardson, microservices.io |
| Modular Monolith | Popularised by Simon Brown and Kamil Grzybek |
| ADR | Michael Nygard (2011) |
| Six-part QA scenario, tactics | Bass, Clements & Kazman; SEI ATAM materials |
| Product quality model (nine characteristics) | ISO/IEC 25010:2023 |
| Business capability | BIZBOK, TOGAF |

---

## Appendix B — What is *not* canonical

Be able to say this when challenged.

| Idea | Status |
|---|---|
| Domain event vs integration event | A community convention, not from Evans. Widely used; useful here. |
| Integration command as a distinct published-message type | Convention. Some literature calls everything an "integration event." The distinction is still worth making. |
| Use-case slice ↔ vertical slice mapping | A synthesis. No published IJI material connects Use-Case 3.0 to DDD, CQRS or event sourcing. Treat as a working hypothesis, not doctrine. |
| Event Modeling patterns → slice types | Community technique, no standards body. Its "automation" pattern deliberately blurs policy and process manager. |
| "One aggregate per transaction" | A strong default from Vernon, not a law. Violate deliberately, with a reason. |
| No cross-module joins | A constraint held firmly (§6/C2), not a definition of modular monolith. |
| Capability vs subdomain distinction | Genuinely contested. Some treat them as near-synonyms; others as business-architecture vs DDD artefacts. Pick one, define it, be consistent. |
| Saga vs process manager | Ambiguous industry-wide. |
| Crypto-shredding as GDPR erasure | Legally unsettled. See §19. Not an architectural determination. |

---

## Appendix C — Open questions

Things this model deliberately leaves for you.

1. **Does Art 17(3) exempt the statutory register from erasure?** A legal question that blocks the §19 design. Ask counsel before the event schemas are fixed.
2. **Which event types need cryptographic signing, not just logging?** §19. Affects the event envelope, so decide early.
3. **Where do examination criteria live** — code, config, or an effective-dated rule store with its own admin UI? §10. Likely the third, which is a build of its own.
4. **Is Filing one context or two** (Submission vs Compliance)? §4.
5. **Read-consistency strategy per screen.** §9. A product decision, not an architectural one.
6. **Projection rebuild duration budget** at target volume. Measure early; it constrains QA-PERF-04.
