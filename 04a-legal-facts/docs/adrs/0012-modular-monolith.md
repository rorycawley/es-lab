# ADR-0012: Modular Monolith Deployment Unit

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The registry has several bounded contexts (Draft, RegistrationApplication,
RegisteredCompany, Identity, Payment boundary, Notifications). These could be
deployed as microservices. However, the project is a proof-of-concept
micro-registry and the team is small.

## Decision

Deploy as a single modular monolith. Each bounded context is a self-contained
module with its own namespace hierarchy, its own domain logic, and its own
adapters. Modules communicate through well-defined ports — they do not call
each other's internal functions directly.

Module boundaries are enforced by code structure and namespace conventions, not
by network calls.

## Consequences

- Deployment, debugging, and local development are significantly simpler than
  microservices.
- Cross-module transactions are straightforward: all modules share one database
  and one process.
- Module isolation means each module can be extracted to an independent service
  later without changing domain logic — only the transport layer changes.
- A failure in one module can affect the whole process. Background job isolation
  (ADR-0013) mitigates this for scheduled tasks.
- Shared process means shared resource constraints (memory, CPU). Acceptable
  for the current scale; revisit if modules have very different scaling
  requirements.
- Code discipline is required: a module must never bypass its port boundary by
  importing another module's internal namespace.
