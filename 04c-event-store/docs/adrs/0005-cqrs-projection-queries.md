# ADR 0005: CQRS Queries Use Projections

- Status: Accepted
- Date: 2026-08-08

## Context

Cart viewing and support history have read shapes different from the write
model. The specification explicitly requires every query to use a projection.

## Decision

Separate command and query paths. `view-cart` reads a cart-view projection and
`review-cart-change-history` reads an ordered history projection. Query handlers
must not load or fold event streams and must not call command handlers.

Update projections synchronously in the same transaction that accepts events.

## Consequences

- Query models are tailored to actor tasks and remain independent of aggregate
  state representation.
- Successful commands are immediately visible to both queries.
- Projection rebuild tooling can be added later without changing public query
  contracts.
