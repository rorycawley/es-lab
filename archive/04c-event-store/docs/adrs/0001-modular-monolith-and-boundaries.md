# ADR 0001: Modular Monolith and Boundaries

- Status: Accepted
- Date: 2026-08-08

## Context

The cart has six actor tasks that share one event-sourced aggregate but should
not become one horizontal service layer. The domain core must remain free of IO,
and PostgreSQL, SQLite and memory persistence must be replaceable.

## Decision

Build one deployable JVM process as a modular monolith. Organize actor behavior
as vertical slices. Each slice owns one inbound use-case port, handler, HTTP
adapter and tests. Shared cart invariants and pure projectors live under the cart
module. Outbound event-store, projection, idempotency and unit-of-work ports are
implemented by driven persistence adapters. Only `platform.runtime` constructs
concrete adapters.

The domain may not depend on HTTP, JSON, JDBC, lifecycle, configuration,
logging, clocks or identifier generation. The application shell supplies all
effects as values or ports.

## Consequences

- One deployment keeps atomic transactions and operations simple.
- Slice ownership remains visible without duplicating genuine invariants.
- Architecture tests must enforce dependency direction.
- Splitting services is deferred until an independently deployable business
  boundary exists.
