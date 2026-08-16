# ADR-0003: Relational Database For Durable State

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The project needs durable storage for events, command outcomes, outbox records,
and read-model projections. The first implementation is a proof of concept, so
the storage model should be simple, inspectable, and testable.

## Decision

Use a relational database for the first implementation's durable state:

- event streams
- command ledger
- outbox
- process-manager state
- read-model projections

## Consequences

- Database transactions can protect event append, command outcome, and outbox
  record creation.
- SQL constraints can defend infrastructure invariants.
- The event store remains the source of truth; projection tables do not become
  authoritative.
- A later ADR may replace or split storage if the proof of concept grows.
