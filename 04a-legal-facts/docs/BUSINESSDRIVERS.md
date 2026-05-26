# Business Drivers

*Status: Draft.*

This document captures the pressures and constraints that sit around the user
stories. User stories describe observable behaviour; this document explains why
the system is being shaped this way and what architectural qualities must not be
lost during implementation. The detailed architectural characteristics and
fitness functions are maintained in
[ARCHITECTURALCHARACTERISTICS.md](ARCHITECTURALCHARACTERISTICS.md).

## Context

This project models a small fictional companies registry. It is intentionally
limited in scope, but it uses real registry concerns: legal existence, public
inspection, identity, auditability, examination, registration numbers, and the
Register as an authoritative record.

The project is also a learning and design exercise. The goal is not to build a
full national registry in one pass. The goal is to make legal facts explicit,
turn them into business rules, and then implement them with a model that can be
tested rigorously.

## Primary Business Drivers

| Driver | Description | Implication |
|--------|-------------|-------------|
| Legal certainty | A company must not exist merely because a user submitted a form. It exists only when the Registry records the legal registration fact. | `RegisteredCompanyCreated` is the legal existence fact. Approval and registration are separate facts. |
| Authoritative Register | The Register must be the source of truth for registered companies, not a convenient search table. | The event-backed Register is authoritative; read models are disposable projections. |
| Public trust and inspectability | Members of the public must be able to inspect registered companies and rely on the information shown. | Public search and company lookup are core features, even in the micro-registry. |
| Strong audit trail | Registry decisions affect legal existence and public records. Actions must be explainable after the fact. | Events are append-only, permanent, and include actor identity, causation, correlation, and time. |
| Controlled decision-making | Examiners prepare cases; Registrars decide. The system must enforce that separation. | State machines and role checks must prevent premature approval, rejection, or examination actions. |
| Identity confidence | Natural persons acting in the system or appearing in company data must be tied to verified identity records. | The system consumes verified identity claims and records, while proofing/outreach is parked. |
| Operational recoverability | Process managers, projections, notifications, and publication can fail independently. Failures must be recoverable without corrupting legal facts. | Use idempotent commands, process-manager state, outbox publication, and rebuildable read models. |

## Design Principles

- Legal facts are recorded as immutable events.
- Commands express intent; events record facts.
- State transitions are explicit and testable.
- Read models support queries but do not define legal truth.
- Business rules are traceable to the Act.
- Parked concerns are named rather than silently ignored.
- External systems are treated as sources of facts, not hidden implementation
  details.

## Delivery Constraints

This is a proof-of-concept micro-registry. It must prove the legal-facts model
without trying to become a full national registry in one slice.

The following constraints apply:

- Keep the first implementation small enough to test end to end.
- Prefer explicit parked scope over half-modelled behaviour.
- Defer concerns that need separate legal or product design.
- Do not introduce infrastructure whose only purpose is future scale.
- Keep the model understandable enough for the Act, rules, tests, and code to
  stay aligned.

## Technical Constraints And Decisions

These decisions are captured as ADRs under `docs/adrs/`.

| Area | Current Direction | ADR Needed |
|------|-------------------|------------|
| Event store | Use an append-only event store as the authoritative Register. | [ADR-0001](adrs/0001-event-sourced-register.md) |
| Read models | Build disposable projections for public search and operational views. | [ADR-0002](adrs/0002-read-model-projections.md) |
| Database | Use a durable relational store for events, command ledger, outbox, and projections unless a later ADR changes this. | [ADR-0003](adrs/0003-relational-database.md) |
| Command idempotency | Use a command ledger keyed by command ID; causation ID is trace metadata only. | [ADR-0004](adrs/0004-command-ledger-idempotency.md) |
| Messaging | Use a transactional outbox so event persistence and publication are recoverable. | [ADR-0005](adrs/0005-transactional-outbox.md) |
| Process managers | Use explicit process-manager state for workflows that cross aggregate boundaries. | [ADR-0006](adrs/0006-process-managers.md) |
| Identity | Trust an OIDC identity broker for verified user identity claims; trust verified-person records for people in company data. | [ADR-0007](adrs/0007-identity-broker-and-verified-person-records.md) |
| Payments | Treat payment as part of submission, but keep payment gateway integration outside this contract. | [ADR-0008](adrs/0008-payment-boundary.md) |
| Deployment | Deployment is deferred until implementation shape is clearer. | [ADR-0009](adrs/0009-deployment-model-deferred.md) |

## Mandatory Fitness Functions

These system-wide checks are mandatory from the first implementation slice.
The architectural-characteristics document defines the detailed expected checks.

| Fitness Function | What It Protects |
|------------------|------------------|
| No company exists without `RegisteredCompanyCreated`. | Legal existence is not inferred from approval, submission, or read-model rows. |
| Read models can be rebuilt from events. | The Register read model remains disposable and non-authoritative. |
| Events are append-only and must never be updated or deleted. | Legal audit integrity. |
| Duplicate command IDs return the recorded outcome. | Idempotency and payment/process-manager safety. |
| Multiple events may share a causation ID. | Processes can emit chains of facts without abusing causation uniqueness. |
| Approval and rejection are accepted only in `ReadyForDecision`. | Separation of examination and Registrar decision. |
| Registration notification is sent only after `RegisteredCompanyCreated`. | Applicants are not told a company exists before it legally exists. |
| Public search returns struck-off and dissolved companies with status. | Public Register history remains inspectable. |
| Register read model is written only by projectors. | No direct mutation of derived state. |
| Parked concerns do not leak into active acceptance criteria. | Scope remains honest and deliverable. |

## Parked Business Drivers

These are important, but not active drivers for the current micro-registry:

- Name reservation and name locking
- Proposed-director outreach for identity proofing
- Identity verification revocation and re-verification
- Full outstanding-obligations model for dissolution
- Rich historical person/director search
- Payment gateway implementation details
- Notification preferences
- Supervisor workflows such as Examiner reassignment

## Open Questions

No open business-driver questions at this point. New questions should be added
here as they arise and either resolved into this document, an ADR, or parked
scope.
