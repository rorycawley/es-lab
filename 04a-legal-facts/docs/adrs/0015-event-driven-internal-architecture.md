# ADR-0015: Event-Driven Internal Architecture

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Deciders produce events. Multiple components need to react to those events:
projectors update read models, process managers advance workflows, the audit log
writer records decisions, and the outbox publisher queues events for external
consumers.

The system runs as multiple instances of the modular monolith for availability
and scalability. An in-process event bus alone is insufficient for this
scenario: events published on Instance A's local bus are invisible to Instance
B and C. A process manager or projector on Instance B will never see an event
that only appeared on Instance A's bus.

Three distinct communication paths are required, each serving a different
purpose.

## Decision

### Path 1 - In-process bus (synchronous, same-transaction)

Used only for consumers that must react within the same database transaction as
the event append. Currently one consumer: the audit log writer, which must
write an audit entry atomically with the domain event.

The bus is in-process and synchronous. A failure in a synchronous consumer
rolls back the transaction, including the event append. This is intentional:
the audit log must either be written atomically or the command must fail.

No projector and no process manager subscribes to the in-process bus. They use
Path 2.

### Path 2 - Event store polling (async, cross-instance safe)

Projectors and process managers do not subscribe to the in-process bus. Instead,
they run as background threads (ADR-0013) that poll the `events` table using the
`global_position` column as a monotonic checkpoint.

Each projector and process manager tracks its own last-processed position.
Threads wake on a short interval (configurable, default 200ms), query events
since their checkpoint, process them, and update their checkpoint atomically.
Because the checkpoint and the projection/PM state update are written in the
same database transaction, the consumer is exactly-once under normal conditions
and at-least-once after a crash (it re-processes the last batch).

This path works correctly across multiple instances because all instances read
from the same Postgres database. Coordination between instances is handled by
database advisory locks: only one instance holds the lock for a given consumer
(e.g., `company-register-projector`, `approval-process-manager`) at a time.
If the lock-holding instance fails, another instance acquires the lock and
resumes from the last persisted checkpoint.

Process managers are additionally protected by optimistic concurrency on the
`process_instances` table (version column): if two instances attempt to advance
the same process manager step concurrently, one will fail and retry, finding
the step already complete.

### Path 3 - Transactional outbox (async, external systems)

Events that need to reach systems outside the process boundary use the
transactional outbox (ADR-0005). The outbox worker (Path 2 consumer) reads
unpublished outbox rows and delivers them to external systems (email service,
future external consumers). This path is explicitly not used for internal
workflow coordination.

## Pattern Terminology

The in-process bus (Path 1) is **not** the Mediator pattern. The distinction
matters for implementation:

- **Command Dispatcher (Mediator pattern):** routes one command to exactly one
  handler. One sender, one handler, one result. Used to dispatch `SubmitDraft`
  to the Draft Decider.
- **In-process Event Bus (pub/sub):** delivers one event to zero or more
  subscribers. The publisher does not know who is subscribed and receives no
  result. Used to deliver domain events to the audit writer.

These are two separate mechanisms in the codebase. Conflating them leads to
designs where command handlers accidentally receive events, or where the audit
writer is expected to return a result.

## Consequences

- The in-process bus has one consumer (audit log writer). Adding new in-process
  bus consumers requires a deliberate decision, because they run synchronously
  in the command transaction.
- Projectors and process managers are safe across multiple instances. There is
  no split-brain or duplicate side-effect risk, given advisory lock and
  optimistic concurrency controls.
- Polling adds latency (up to one polling interval, default 200ms) between
  event append and projector/PM reaction. This is acceptable for a registry
  system. If lower latency is required, Postgres `LISTEN/NOTIFY` can trigger
  polling threads immediately without changing the overall model.
- The polling interval is configurable. It must never be hardcoded.
- If a projector falls behind (e.g., after a crash), it catches up by processing
  the backlog. Read models may be temporarily stale during catchup. Queries that
  require ground truth must use the write-side event store, not the read model.
- Internal event schema (on the bus or in the events table) is not a public
  contract. External event schema (published via the outbox) is a public
  contract and must be versioned (ADR-0022).
