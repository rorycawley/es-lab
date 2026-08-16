# Architecture Decision Records

ADRs record decisions that constrain later use-case realizations. They use the
status values Proposed, Accepted, Superseded and Deprecated. A changed decision
gets a new ADR that links to and supersedes the old one; accepted records are
not rewritten to hide the original context.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-and-boundaries.md) | Modular monolith, vertical slices, ports and adapters | Accepted |
| [0002](0002-event-sourcing-and-atomic-projections.md) | Event sourcing and atomic internal projections | Accepted |
| [0003](0003-signed-cart-observation-tokens.md) | Signed opaque cart observation tokens | Accepted |
| [0004](0004-postgresql-optimistic-concurrency.md) | PostgreSQL optimistic concurrency | Accepted |
| [0005](0005-cqrs-projection-queries.md) | CQRS queries use projections | Accepted |
| [0006](0006-global-command-request-serialization.md) | Global command request-ID serialization | Accepted |
| [0007](0007-trusted-deployment-boundary.md) | Trusted deployment boundary | Accepted |
