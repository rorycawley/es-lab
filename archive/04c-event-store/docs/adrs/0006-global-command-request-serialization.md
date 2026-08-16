# ADR 0006: Global Command Request-ID Serialization

- Status: Accepted
- Date: 2026-08-08

## Context

A command request UUID identifies one logical command across the backend, not
only within one cart. Identical deliveries must share one result, while
different inputs racing on a new UUID must not both succeed on different carts.

## Decision

Serialize the request UUID globally before appending any stream event. Within
the transaction, recheck the accepted-command record. Semantically equal input
returns the stored business result; non-equal input is invalid. If different
otherwise acceptable inputs race on a new UUID, exactly one is accepted and all
non-equal losers are invalid.

Only accepted commands persist request IDs. Invalid, business-rejected and
conflicting attempts release serialization without consuming the UUID.

PostgreSQL will use a transaction-scoped request-key lock, SQLite an immediate
write transaction, and memory one atomic state transition.

## Consequences

- Request-ID locking is independent of cart-stream locking.
- Canonical command equality is semantic rather than JSON byte equality.
- Stored replay data excludes delivery-specific correlation and tracing fields.
