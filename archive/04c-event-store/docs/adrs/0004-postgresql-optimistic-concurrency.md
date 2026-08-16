# ADR 0004: PostgreSQL Optimistic Concurrency

- Status: Accepted
- Date: 2026-08-08

## Context

Distinct logical commands based on one cart observation must not both change the
cart. The actor, not the server, chooses when to reconsider after a conflict.

## Decision

For an existing stream, append only when its current revision equals the
revision decoded from the supplied observation. Perform the comparison, event
append, projection updates and accepted-result write in one PostgreSQL
transaction. Use row locking or an equivalent compare-and-swap statement backed
by unique stream-revision constraints.

The first addition uses an internal absent-stream expectation. Never expose raw
expected revisions, `:any` or absent-stream sentinels through HTTP. Never retry
a revision conflict automatically.

## Consequences

- At most one distinct command can consume a current observation.
- Losing commands return a stable conflict and make no partial change.
- SQLite and memory adapters must pass the same concurrency contract with their
  own atomic mechanisms.
