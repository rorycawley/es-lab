# ADR-0011: CQRS Architecture

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Event sourcing (ADR-0001) records the write side as an append-only event
stream. Queries need a different shape: filtered, projected, paginated, and
joined. Serving queries directly from the event stream would be slow and would
expose internal event structure to API consumers.

## Decision

Adopt Command Query Responsibility Segregation (CQRS). Commands flow through
Deciders that validate, produce events, and return outcomes. Queries are served
exclusively from read-model projections maintained by projectors.

The write side (commands → events) and read side (projections → queries) are
separately deployed concerns within the modular monolith. They share the same
database but no code path is shared between them except the event store.

## Consequences

- Write-side consistency is strong: events are the source of truth.
- Read-side consistency is eventual: projections lag behind the event store by
  the projector processing time.
- Read models can be optimised independently for query patterns.
- Read models are disposable: if a projection is dropped or corrupted, it can
  be rebuilt from events (ADR-0002, ADR-0023).
- Commands must never read from projections to enforce business rules - they
  must read from the write-side event store or aggregate state.
- Adds complexity: two models for every domain concept. This is acceptable
  given the legal integrity requirements that demand strong write-side
  consistency.
