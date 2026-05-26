# ADR-0009: Deployment Model Deferred

*Status: Deferred.*
*Date: 2026-05-26.*

## Context

This is currently a proof-of-concept micro-registry. The legal and behavioural
model is more important than committing early to a production deployment model.

## Decision

Do not choose a final deployment model yet.

The first implementation should use the smallest local deployment that can run
the application, event store, tests, process managers, and projections
reliably.

## Consequences

- Deployment decisions will be revisited when implementation shape is clearer.
- ADRs must be added before introducing production-specific infrastructure.
- The proof of concept should avoid infrastructure that exists only for future
  scale.
