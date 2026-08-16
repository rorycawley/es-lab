# ADR-0013: In-Process Threads for Background Jobs

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Several registry processes run on a schedule or in response to events rather
than in response to HTTP requests: draft expiry, requisition deadline checks,
expiry-warning notifications, outbox publication, projection updates, and
process manager step retries.

These could be implemented as separate worker processes, a distributed job
queue (Sidekiq, Temporal, etc.), or as in-process threads.

## Decision

Run background jobs as in-process threads within the modular monolith. Each
job is a named, supervised component started by the system lifecycle. Jobs are
idempotent and restartable: if the process restarts, jobs resume from durable
state (process manager records, outbox records, scheduler watermarks).

## Consequences

- No additional deployment unit, job queue, or messaging infrastructure for
  internal scheduling.
- Simplified local development: one process contains everything.
- Background jobs must be designed to be idempotent and to recover from
  mid-run crashes. This is required regardless of the deployment model, but
  is especially important when there is no distributed coordinator.
- Job state is durable: schedulers record their last-run watermark in the
  database. On restart, they catch up from the last position.
- Scaling background job throughput requires scaling the whole monolith. If
  individual jobs become resource-intensive, extract them to a separate process
  in a new ADR.
- Supervision strategy: a component manager (e.g., Integrant) must restart
  failed job threads and alert on repeated failures.

### Multi-instance coordination

Multiple instances of the monolith may run simultaneously for availability and
scalability. A background job thread running on Instance A must not duplicate
work already being done on Instance B.

Each background job acquires a named database advisory lock before doing work.
Only the instance holding the lock runs the job at any given time. If the
lock-holding instance fails, another instance acquires the lock on its next
wake cycle and resumes from the last persisted checkpoint. No external
coordinator or distributed lock service is required.

Projectors and process managers do not subscribe to the in-process event bus.
They use the event store polling path (ADR-0015 Path 2): each thread reads new
events from the `events` table using the `global_position` column as a
monotonic checkpoint, processes them, and updates its checkpoint atomically.
This is correct across all instances because every instance reads from the same
Postgres database.
