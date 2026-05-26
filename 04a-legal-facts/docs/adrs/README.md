# Architectural Decision Records

This folder records architectural decisions for the 04a legal-facts project.

ADRs are intentionally short. They capture decisions that user stories and
acceptance criteria do not explain well: structural choices, constraints,
tradeoffs, and consequences.

## Index

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-event-sourced-register.md) | Event-sourced Register as source of truth | Accepted |
| [0002](0002-read-model-projections.md) | Disposable read-model projections | Accepted |
| [0003](0003-relational-database.md) | Relational database for durable state | Accepted |
| [0004](0004-command-ledger-idempotency.md) | Command ledger for idempotency | Accepted |
| [0005](0005-transactional-outbox.md) | Transactional outbox for publication | Accepted |
| [0006](0006-process-managers.md) | Process managers for cross-aggregate workflows | Accepted |
| [0007](0007-identity-broker-and-verified-person-records.md) | Identity broker and verified-person records | Accepted |
| [0008](0008-payment-boundary.md) | Payment belongs to submission boundary | Accepted |
| [0009](0009-deployment-model-deferred.md) | Deployment model deferred | Deferred |
