# ADR-0017: Optimistic Concurrency for Event Appending

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Multiple concurrent commands may attempt to append events to the same aggregate
event stream simultaneously. Without a concurrency guard, two commands could
each read the same aggregate state, both pass validation, and both append events
- producing an inconsistent stream.

Pessimistic locking (SELECT FOR UPDATE on the stream) would prevent this but
would serialise all commands against the same aggregate, limiting throughput.

## Decision

Use optimistic concurrency control on every event append. When a command runs,
it reads the current stream version (the count of events in the stream). The
append operation specifies the expected version; the database rejects the
append if the actual version differs. On conflict, the command is retried: the
aggregate state is reloaded from the latest events and the Decider is re-run.

Combined with idempotent commands (ADR-0004), retries are safe: re-running a
command with the same command ID returns the previously recorded outcome.

## Consequences

- No locks held during business logic execution. Multiple aggregates can
  process commands concurrently without contention.
- Write contention on a single high-traffic aggregate will cause retries.
  Small, focused aggregates (Architecture Principle) reduce the probability of
  contention on any single stream.
- Decider functions must be pure and side-effect-free so they can safely be
  re-run on retry. This is already required by the domain purity principle.
- The append operation must be atomic with the command ledger entry (ADR-0004)
  and outbox write (ADR-0005). All three happen in a single database
  transaction.
- Infinite retry loops are prevented by a maximum retry count with a final
  error outcome.
