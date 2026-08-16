# Business Drivers

*Status: Draft.*

This document captures the pressures and constraints that sit around the user
stories. User stories describe observable behaviour; this document explains why
the system is being shaped this way and what architectural qualities must not be
lost during implementation. The detailed architectural characteristics and
fitness functions are maintained in
[06-architectural-characteristics.md](06-architectural-characteristics.md).

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

*Design principles have been moved to [05-architecture-principles.md](05-architecture-principles.md).*

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

*Mandatory fitness functions have been moved to [06-architectural-characteristics.md](06-architectural-characteristics.md).*

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
