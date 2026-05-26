# ADR-0005: Transactional Outbox For Publication

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The system records domain events and publishes events to downstream handlers,
projectors, and process managers. Persisting an event and publishing a message
are separate operations, so a crash between them must not lose facts.

## Decision

Use a transactional outbox. The event append and outbox record are committed in
the same database transaction. A publisher later publishes pending outbox
records and marks them published.

## Consequences

- Event persistence does not depend on broker availability.
- Publication is at-least-once.
- Consumers and process managers must be idempotent.
- Tests must cover retry and duplicate-publication behaviour.
