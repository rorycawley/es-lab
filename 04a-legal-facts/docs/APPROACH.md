# Approach

This document is the entry point for the `04a-legal-facts` project. It
explains the golden thread - the ordered chain of artifacts that runs from
**WHY** the system exists through **WHAT** it must do to **HOW** it is built -
and the rationale for that order. Later artifacts always build on decisions
already made in earlier ones.


## The Golden Thread

The documents in this project are numbered to make the reading order explicit:

```
00  The law - the source of all requirements
01  WHY: Vision and Mission - what the world looks like when we succeed, and why we exist
02  WHY: Strategic Goals - what success looks like in this period
03  WHY: Business Drivers - why the system is shaped this way
04  WHAT: Domain Discovery - shared language, boundaries, aggregates, events
05  HOW: Architecture Principles - standing rules that govern every decision
06  HOW: Architectural Characteristics - NFRs and the fitness functions that verify them
07  WHAT: Business Rules - enforceable rules derived from the Act, including FSMs
08  WHAT: User Stories - observable behaviour with acceptance criteria
09  HOW: Testing Strategy - test layers, git gates, mocking, CI configuration
     adrs/  - Structural decisions that user stories and rules do not explain
```

The unnumbered documents - `APPROACH.md` (this file) and `adrs/README.md` - are
navigation aids, not part of the golden thread.


## Guiding Idea

The law is the requirements document. Every rule must trace to a legal source.
Every test must trace to a rule. Every architecture decision must trace to a
characteristic. Without this chain, you cannot answer "why does this code
exist?" six months later - and in a regulated system, that question will
be asked.


## Artifact Sequence and Rationale

### 0. The Act

*What does the law require?*

`00-companies-registration-act.md` is the source of truth. All business rules,
user stories, and acceptance criteria are traceable to sections of the Act.

**Document:** [`00-companies-registration-act.md`](00-companies-registration-act.md)


### 1. Vision and Mission

*What does the world look like when we have fully succeeded? Why do we exist?*

The **Vision** is the long-term aspiration - the 10–20 year horizon that gives
the organisation its sense of direction. It does not describe features or
systems; it describes the state of the world the organisation exists to bring
about.

The **Mission** is the organisation's enduring purpose. It names what the
organisation does every day and who it serves. Goals must be justifiable by the
Mission. Work that cannot be traced to the Mission should not exist.

The document also defines the full strategic hierarchy: Vision, Mission,
Drivers, Goals, Objectives, KPIs - and explains what each level means and why
they are distinct.

**Document:** [`01-vision-and-mission.md`](01-vision-and-mission.md)


### 2. Strategic Goals

*What does success look like? What metrics or observable outcomes confirm the
system is working?*

Strategic goals give every downstream decision a reference point. Without a
clear definition of success, there is no natural stopping condition and no
basis for prioritisation.

**Document:** [`02-strategic-goals.md`](02-strategic-goals.md)


### 3. Business Drivers

*Why is the system being shaped this way? What pressures and constraints sit
around the requirements?*

Business drivers explain the motivation behind structural choices. They are not
the same as requirements (which describe behaviour) - they explain why certain
qualities matter more than others. Without drivers, architectural characteristics
have no grounding and ADRs cannot be justified.

**Document:** [`03-business-drivers.md`](03-business-drivers.md)


### 4. Domain Discovery

*What are the shared concepts, language, and boundaries?*

Before writing rules or stories, establish a ubiquitous language glossary and
a bounded context map. This ensures the Act, the business rules, the tests, and
the code use the same words for the same things. Language drift is one of the
most common causes of bugs in complex domains.

For this project, the bounded contexts are: **Draft**, **RegistrationApplication**,
**RegisteredCompany**, **Identity**, **Payment boundary**, **Notifications**, and
**PublicRegister**.

**Document:** [`04-domain-discovery.md`](04-domain-discovery.md)


### 5. Architecture Principles

*What rules guide all decisions?*

Principles come before characteristics and ADRs. A principle is a standing
rule that applies to every decision - "domain logic is pure",
"the Register is always authoritative", "events are institutional facts".
Characteristics and ADRs must be consistent with principles. If an ADR appears
to contradict a principle, the ADR must explicitly justify the exception.

**Document:** [`05-architecture-principles.md`](05-architecture-principles.md)


### 6. Architectural Characteristics (NFRs)

*What qualities must the system have?*

Architectural characteristics are the cross-functional requirements that
constrain the design: legal integrity, auditability, idempotency, fault
tolerance, rebuildability. They are derived from business drivers. Each
characteristic has mandatory fitness functions - tests that run continuously
and fail the build if the characteristic is violated.

**Document:** [`06-architectural-characteristics.md`](06-architectural-characteristics.md)


### 7. Business Rules

*What are the enforceable rules? What are the state boundaries?*

Business rules are the constraints derived from the Act. They include the
finite state machine transition specs (as authoritative EDN) that govern what
commands are legal in each aggregate state.

**Document:** [`07-business-rules.md`](07-business-rules.md)


### 8. Architectural Decision Records (ADRs)

*What specific structural decisions have been made, and why?*

ADRs record decisions that user stories and business rules do not explain:
technology choices, structural patterns, and their consequences. Each ADR
states context, decision, and consequences. ADRs must be consistent with
Architecture Principles.

**Directory:** [`adrs/`](adrs/README.md)


### 9. Event Modeling

*What commands, events, and workflow steps exist? In what sequence?*

Event modeling (Event Storming or the Adam Dymitruk model) surfaces the
commands, domain events, process managers, and read models before any code is
written. It is the bridge between business rules and implementation. The output
is a sequence-ordered map of: **Commands → Events → Read Models → Process
Manager triggers → next Commands**. This directly informs the user stories,
the aggregate boundaries, and the integration tests.

Event modeling should happen alongside or before user stories - it reveals the
command/event vocabulary that user stories rely on.

**Status in this project:** To be completed. Event model to be created using
a dedicated tool (EventModeling.org notation or equivalent) and its artefacts
stored in `docs/event-model/`.


### 10. User Stories with Acceptance Criteria

*What must the system do?*

User stories describe observable user and system behaviour. Acceptance criteria
are the specific testable outcomes. Each user story carries traceability
metadata linking it back to:
- The Act section(s) it implements (e.g. `§14`)
- The business rules it exercises (e.g. `BR-AP-001`)
- The architectural characteristics it tests (e.g. `legal-integrity`)
- The fitness functions that verify it (e.g. `FF-001`)
- The ADRs that govern its structural decisions (e.g. `ADR-0010`)

See **Traceability** section below for the format.

**Document:** [`08-user-stories.md`](08-user-stories.md)


### 11. Architecture Descriptions and Diagrams

*How does the system fit together at each level of abstraction?*

Diagrams are produced after the structural decisions (ADRs) are settled but
before implementation begins. They communicate the system shape to all
stakeholders. The following diagrams are required:

| Diagram | Level | What It Shows |
|---------|-------|---------------|
| C4 System Context (Level 1) | System | The registry system, its users (Applicant, Examiner, Registrar, Public), and external systems it depends on (Identity Broker, Payment Gateway, Address Validation, Email). |
| C4 Container (Level 2) | Containers | The modular monolith, Postgres database, message broker, and their relationships. Each module shown as a component within the monolith. |
| Infrastructure Diagram | Deployment | Containers, network topology, load balancers, and how the system is deployed in production. Updated when ADR-0009 is resolved. |
| Data Architecture | Data | The event store schema, read model schemas, audit log schema, command ledger schema, and outbox schema. How data flows from write side to read side. |

Diagrams are maintained as Mermaid source embedded in the document so they
render in GitHub and most editors without external tooling.

**Document:** [`10-architecture.md`](10-architecture.md)


### 12. TDD Implementation with CI and Trunk-Based Development

*How is the system built?*

Implementation follows the test layers defined in the Testing Strategy
([`09-testing-strategy.md`](09-testing-strategy.md)). The detailed workflow
below is the authoritative development loop. It incorporates trunk-based
development, behavior preservation, test isolation, and LLM pairing, tailored
for a highly modular Clojure system backed by a Postgres event store.

This is the blueprint for **architecting for the unknown** - separating the
*what* (the stable behavior preserved by tests) from the *how* (the fluid
implementation details that will change).


#### The TDD Loop

Every unit of work turns the same three-step crank (Kent Beck, *Test-Driven
Development: By Example*):

| Step | Instruction | What it means in practice |
|------|-------------|--------------------------|
| **1. Red** | Write a little test that doesn't work, and perhaps doesn't even compile at first. | Small and focused. Targets the public boundary. Must fail before any implementation exists. |
| **2. Green** | Make the test work quickly, committing whatever sins necessary in the process. | Satisfy the behavior as fast as possible. Hardcode, duplicate, ignore structure. Code quality is irrelevant until the behavior is proven. |
| **3. Refactor** | Eliminate all of the duplication created in merely getting the test to work. | The target is specifically the duplication introduced by Green. Not a general tidy-up. |

The four phases below map directly onto this loop: Phase 1 ends at Red,
Phase 2 is Green, Phase 3 is Refactor, Phase 4 integrates the result.
The loop repeats for every acceptance criterion.


#### Phase 1 - Discovery and API Design (The Contract)

Before touching the implementation, define the boundary and design the
contract. The goal here is not testing - it is **ergonomic API design and
behavior preservation**. Tests must target the public boundary so that the
implementation can change freely without breaking the test.

1. **Identify the driver.** Start with the business pressure. What exact
   system behavior must be preserved, regardless of how the internals change
   in three years? Translate this into a clear *Given / When / Then* format
   with a named acceptance criterion ID (e.g. `AC-AP-007-001`).

2. **Target the component interface.** Pinpoint the exact public boundary
   (the module's port or HTTP endpoint) that will expose this behavior.

3. **Prompt the LLM as the first consumer.** Ask the LLM to write a test that
   calls this public API.
   > *Prompt strategy:* "We need to lock in this specific behavior:
   > [Given/When/Then]. Write a developer test targeting the public boundary of
   > this module. The goal is to preserve this exact behavior. Ensure the test
   > creates its own fresh state and does not rely on global variables or
   > shared fixtures."

4. **Evaluate the ergonomics.** Review the generated test. Is the module easy
   to call? Is the API intuitive? If it requires massive setup or leaks Postgres
   or event store implementation details, the design is wrong. Redesign the
   test with the LLM until the contract is elegant.

5. **Watch it fail (Red).** Run the isolated test locally. It must fail.
   The safety net is now armed.


#### Phase 2 - The Implementation (MMMSS)

Move in **Many More Much Smaller Steps** to satisfy the contract. Speed and
functionality are the only goals here.

1. **Write the duct-tape (Green).** Prompt the LLM for the fastest, simplest
   Clojure code to make the test pass. Do not worry about clean namespaces,
   pure functions, or optimal queries yet. Just get to green.

2. **Build the scaffold (optional).** If the internal algorithm is highly
   complex, use the LLM to write tiny, granular tests targeting private
   functions just to work out the logic.

3. **Tear down the scaffolding.** Once the main behavioral test is green,
   **delete all granular scaffold tests.** Keeping them turns them into an
   obstacle to future change.


#### Phase 3 - The Preservation Refactor

With behavior securely locked in and the test passing, mold the duct-tape code
into an enduring architecture.

1. **Optimize the internals.** Use the LLM to clean up the implementation.
   Extract pure functions, decouple logic from infrastructure, and optimise
   data structures. This is the moment to enforce the architecture principles
   (pure Deciders, ports and adaptors, no I/O in domain logic).

2. **The golden rule.** **Do not write new tests during this phase.**

3. **Rely on the shield.** As you restructure, keep running the behavioral
   test. If it stays green, the behavior is preserved. If it breaks, the test
   is coupled to the implementation - fix the test to target the boundary again,
   not the internals.


#### Phase 4 - Continuous Integration (The Trunk)

A clean module and a preserved behavior are ready to integrate.

1. **Run the full local suite.** Execute the entire test suite. Because every
   test creates its own state and tears it down, they run fully in parallel
   and finish in seconds.

2. **Push to trunk.** Commit directly to `main`. No feature branches. No
   asynchronous pull requests. Every commit leaves the system in a releasable
   state.

3. **Automated verification.** Pushing to GitHub triggers the GitHub Actions
   pipeline, which runs the full suite to ensure no preserved behavior was
   violated.

4. **Deploy.** If the suite is green, GitHub Actions automatically deploys the
   updated artifact to the target environment.

5. **The 10-minute rule.** If the build goes red, a preserved behavior has been
   broken. Stop all new development, swarm the issue, and push a fix or revert
   within 10 minutes to keep the trunk pristine.


## Traceability

The chain from requirement to test must be navigable in both directions:

```
Business Driver
  → Architectural Characteristic (06-architectural-characteristics.md)
    → ADR (adrs/)
      → User Story (08-user-stories.md)
        → Acceptance Criterion
          → Test (named by AC ID)
```

And in reverse:
```
Test → Acceptance Criterion → User Story → Characteristic → Driver
```

### User Story Format

Each user story's References block carries the following fields:

```
**References:** §X, BR-XX-nnn
**Characteristics:** legal-integrity, auditability
**Fitness functions:** FF-001, FF-003
**ADRs:** ADR-0001, ADR-0010
```

### Traceability Matrix

The traceability matrix in `06-architectural-characteristics.md` maps each
characteristic to the user stories that exercise it and the fitness functions
that verify it. This provides the reverse lookup: given a characteristic,
find the stories and tests.

### Test Naming

Every test that verifies an acceptance criterion is named with the AC ID:

```clojure
(deftest AC-AP-007-001-submit-active-draft ...)
(deftest FF-001-no-company-without-registered-company-created ...)
```

This means any test failure can be immediately traced back to a requirement,
and any requirement can be immediately traced forward to its test.


## Repeatable Model

This approach is designed for **regulated, workflow-heavy, audit-critical
systems** where:

- Rules must trace to a legal or institutional source.
- Events are permanent institutional records.
- The audit trail must be inspectable years after the fact.
- State machines govern who can do what, and when.
- Multiple actors with different roles interact with overlapping data.

For simpler domains (CRUD, no regulatory requirements, trivial state), this
framework adds more overhead than value. Before applying it, ask: does this
system need to answer "why did this happen?" five years from now? If yes,
apply the full framework. If no, apply only the parts that add value.


## Document Index

| Document | Purpose |
|----------|---------|
| [`00-companies-registration-act.md`](00-companies-registration-act.md) | Source law - all rules and stories trace here |
| [`01-vision-and-mission.md`](01-vision-and-mission.md) | Long-term aspiration, enduring purpose, constituencies, and the strategic hierarchy explained |
| [`02-strategic-goals.md`](02-strategic-goals.md) | Strategic goals, measurable objectives, KPIs |
| [`03-business-drivers.md`](03-business-drivers.md) | Pressures, constraints, and motivation behind structural choices |
| [`04-domain-discovery.md`](04-domain-discovery.md) | Domain, ubiquitous language, subdomains, bounded contexts, context map, aggregates, commands, events |
| [`05-architecture-principles.md`](05-architecture-principles.md) | Standing rules that govern all decisions |
| [`06-architectural-characteristics.md`](06-architectural-characteristics.md) | NFRs, fitness functions, and traceability matrix |
| [`07-business-rules.md`](07-business-rules.md) | Enforceable rules derived from the Act, including FSM EDN specs |
| [`08-user-stories.md`](08-user-stories.md) | Observable behaviour with acceptance criteria and traceability metadata |
| [`09-testing-strategy.md`](09-testing-strategy.md) | Test layers, git gates, mocking strategy |
| [`10-architecture.md`](10-architecture.md) | C4 system context, C4 containers, data architecture, security architecture |
| [`11-definition-of-done.md`](11-definition-of-done.md) | Bilateral agreement on what "done" means, the acceptance protocol, and sign-off matrix |
| [`adrs/README.md`](adrs/README.md) | Index of all architectural decision records |
| `docs/event-model/` | Event model artifacts *(to be created)* |


## Parked Scope

Several important registry concerns are deliberately deferred. Parked concerns
are named, not silently ignored. See [`03-business-drivers.md`](03-business-drivers.md)
for the full parked list.


## References

- https://news.ycombinator.com/item?id=48272984
- https://www.youtube.com/watch?v=A_e90lKVUwo
