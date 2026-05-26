# Architectural Decision Records

This folder records architectural decisions for the 04a legal-facts project.

ADRs are intentionally short. They capture decisions that user stories and
acceptance criteria do not explain well: structural choices, constraints,
tradeoffs, and consequences. All ADRs must be consistent with the Architecture
Principles defined in `docs/05-architecture-principles.md`.

## Index

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-event-sourced-register.md) | Event-sourced Register as source of truth | Accepted |
| [0002](0002-read-model-projections.md) | Disposable read-model projections | Accepted |
| [0003](0003-relational-database.md) | Relational database (Postgres) for durable state | Accepted |
| [0004](0004-command-ledger-idempotency.md) | Command ledger for idempotency | Accepted |
| [0005](0005-transactional-outbox.md) | Transactional outbox for publication | Accepted |
| [0006](0006-process-managers.md) | Process managers for cross-aggregate workflows | Accepted |
| [0007](0007-identity-broker-and-verified-person-records.md) | Identity broker and verified-person records (OIDC authentication) | Accepted |
| [0008](0008-payment-boundary.md) | Payment belongs to submission boundary | Accepted |
| [0009](0009-deployment-model-deferred.md) | Deployment model deferred | Deferred |
| [0010](0010-finite-state-machines-for-aggregates.md) | Finite state machines as aggregate state model | Accepted |
| [0011](0011-cqrs-architecture.md) | CQRS architecture | Accepted |
| [0012](0012-modular-monolith.md) | Modular monolith deployment unit | Accepted |
| [0013](0013-in-process-background-jobs.md) | In-process threads for background jobs | Accepted |
| [0014](0014-containerisation-and-orchestration.md) | Containerisation and container orchestration | Accepted |
| [0015](0015-event-driven-internal-architecture.md) | Event-driven internal architecture | Accepted |
| [0016](0016-task-based-ui-endpoints.md) | Task-based endpoints for API commands | Accepted |
| [0017](0017-optimistic-concurrency-for-event-appending.md) | Optimistic concurrency for event appending | Accepted |
| [0018](0018-separate-audit-log-store.md) | Separate audit log store | Accepted |
| [0019](0019-ports-and-adaptors.md) | Ports and adaptors | Accepted |
| [0020](0020-claims-based-authorisation.md) | Claims-based authorisation (RBAC, ABAC, FLS, RLS) | Accepted |
| [0021](0021-versioned-web-api.md) | Versioned web API | Accepted |
| [0022](0022-event-schema-versioning.md) | Event schema versioning | Accepted |
| [0023](0023-projection-rebuilding-strategy.md) | Projection rebuilding strategy | Accepted |
| [0024](0024-process-manager-recovery.md) | Process manager recovery | Accepted |
| [0025](0025-data-classification-model.md) | Data classification model (taxonomy, log masking, audit log access) | Accepted |
