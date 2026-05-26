# ADR-0023: Projection Rebuilding Strategy

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Read-model projections are derived from events and may need to be rebuilt
after a bug fix, a schema migration, a new query requirement, or after a
projector failure that left a projection in an inconsistent state. The rebuild
must not interrupt access to the live read model during the process.

## Decision

Projections are rebuilt by replaying all relevant events from position zero
against a shadow schema. The steps are:

1. Create a shadow schema (or shadow table set) alongside the live schema.
2. Run the projector against all events into the shadow schema from position zero.
3. Once caught up, promote the shadow schema atomically (rename tables or flip
   a schema pointer).
4. Drop the old schema after confirming the promotion is correct.

No snapshotting is implemented for this proof-of-concept. If event volume grows
to the point where replay from zero is impractically slow, a snapshot ADR will
be written to introduce periodic aggregate snapshots.

Projector position is tracked per projector: each projector records the event
store position it last processed. A crashed projector resumes from its last
recorded position; it never reprocesses already-projected events unless a full
rebuild is initiated.

## Consequences

- The live read model remains available and consistent during a rebuild.
- Any projection bug can be corrected by fixing the projector and triggering a
  rebuild — no data is lost because the event store is the source of truth.
- New projections can be introduced by running a projector against historical
  events.
- Rebuild time scales linearly with event volume. For a proof-of-concept with
  bounded data, replay from zero is acceptable.
- Fitness function FF-002 verifies this capability: it drops the projection,
  triggers a replay, and asserts the result matches the expected public query
  output.
