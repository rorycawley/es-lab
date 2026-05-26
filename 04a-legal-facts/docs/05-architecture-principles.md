# Architecture Principles

*Status: Accepted.*
*Date: 2026-05-26.*

These principles apply to the registry system and to any enduring software
that must record and protect legal or institutional facts over long periods.
They are not preferences — they are rules that inform every structural and
implementation decision. ADRs must be consistent with them; where an ADR
appears to contradict a principle, the ADR must explicitly state why the
exception is justified.

---

## Domain Integrity

**The law is the requirements document.**
For regulated systems, every business rule must trace to a legal or
regulatory source. If a rule cannot be traced, it should not exist. If the
law changes, every derived rule and test must be reviewed.

**Events are institutional facts.**
Record what happened, not what you think the current state is. A registered
company exists because `RegisteredCompanyCreated` was recorded, not because
an approval row is in a table. Events are permanent evidence of decisions made
by legal actors at a specific point in time.

**The Register is always authoritative.**
Derived views (read models, projections, caches) serve convenience and
performance. They must never define legal truth. Any workflow that needs ground
truth must query the authoritative write-side record, not a read model.

**Approval and registration are different moments.**
Approving an application and creating a legal entity are causally related but
legally distinct facts. Never conflate them. The process manager that chains
them is explicit, observable, and recoverable.

---

## System Design

**Commands express intent; events record facts.**
A command is a request that may be rejected. An event is a fact that has
already happened and cannot be undone. Never use a past-tense name for a
command or a present-tense name for an event. Never mutate an event.

**Make illegal states unrepresentable.**
Use the type system, state machines, and aggregate boundaries to make it
structurally impossible to express invalid states. An aggregate that cannot
reach an illegal state through its command interface does not need defensive
checks scattered across the codebase.

**Aggregates are small and focused.**
Each aggregate has a single clear responsibility and a state machine simple
enough to fit on one page. Large aggregates with complex state machines are a
design smell. Split the aggregate, not the state machine.

**Separate process from decision.**
Examiners prepare cases; Registrars decide. Process managers coordinate
workflows; Deciders enforce rules. Never allow a coordination mechanism to
also carry business rules.

**Read models are disposable.**
Any projection that cannot be rebuilt from events from scratch is a liability,
not an asset. Treat every read model as temporary. If destroying it would
cause data loss, the design is wrong.

**Prefer explicit over implicit.**
State machines over boolean flags. Named commands over generic updates. Named
events over raw row mutations. An event called `DraftCancelled` is
self-documenting; a row with `status = 'cancelled'` is not.

---

## Implementation

**Domain logic is pure.**
Business rules are functions from `(state, command) → (events | error)`. They
take no I/O, no database connections, no timestamps from the environment.
Infrastructure is injected at the boundary, never imported into the domain.
Pure functions are trivially testable without mocks, stubs, or containers.

**Infrastructure is a plugin.**
Ports and adaptors separate the domain from its delivery mechanism. The domain
defines what it needs (ports); the infrastructure delivers it (adaptors). A
Decider that works with an in-memory event store today must work with a
Postgres event store tomorrow without changing a line of domain code.

**Requirements describe WHAT and WHY, never HOW.**
User stories and business rules constrain behaviour by purpose, not
implementation. A story that says "the system must use a relational database"
is not a requirement — it is an ADR written in the wrong place. ADRs explain
structural decisions; requirements explain observable behaviour.

**Event schemas are permanent contracts.**
An event written today must be readable in five years. Never change an event
schema in place. Introduce a versioned successor and write an evolver (upcaster)
that transforms old versions at read time. This is non-negotiable for
event-sourced systems.

---

## Delivery and Observability

**The system must always answer "why?"**
Every event must record who caused it, when it happened, which command caused
it (causation ID), and which wider workflow it belongs to (correlation ID). A
system that cannot answer "why did this happen?" after the fact has failed its
audit obligation before it goes live.

**Design for recovery, not just correctness.**
Every process that can fail independently (projectors, process managers,
notification senders, publishers) must be able to recover and resume from its
last known good position without corrupting facts. Correctness at steady state
is necessary but insufficient.

**Tests are specifications.**
A behaviour that is not tested is not specified. Acceptance criteria map
directly to tests — the same ID, the same Given/When/Then structure. Fitness
functions are tests that run continuously, not periodic architectural reviews.

**Small steps, always releasable.**
Every commit must leave the system in a state that could be deployed to
production. Large batches of incomplete work are a risk, not a feature.
Trunk-based development with a fast test gate enforces this.

**Parked concerns are visible and named.**
Never silently ignore an out-of-scope concern. Name it, note why it is parked,
and keep it visible in the requirements. This protects the implementation from
accidental scope creep and makes future extensions traceable.

**Fitness functions prevent architectural drift.**
Key architectural qualities are tested continuously, not reviewed periodically.
Every fitness function is a test that runs in CI and fails the build if the
system drifts from its architectural constraints.

---

## Enduring Software

**Defer irreversible decisions.**
Make structural decisions (aggregates, events, commands, module boundaries)
before technology decisions (specific databases, queue systems, deployment
platforms). Reversing a structural decision after implementation is expensive;
swapping a technology is an adaptor replacement.

**Avoid operational magic.**
Configuration that cannot be changed without a code deployment is a liability
for regulated systems. Configurable values (fee amounts, expiry periods,
warning periods, prohibited name categories) must be stored in configuration,
not hardcoded. The Registrar's order changes the configuration; it does not
change the code.

**The audit record is permanent.**
No event may ever be altered or deleted. Corrective action records new facts;
it does not rewrite history. This is both a legal requirement and an
architectural constraint that simplifies the system: if nothing is ever
deleted, you never need to reason about deletion.
