# ADR-0002: Disposable Read-Model Projections

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The public and Registry staff need searchable views of companies,
applications, directors, and statuses. Those views are not the legal source of
truth.

## Decision

Read models are disposable projections derived from domain events. They support
inspection and search, but they do not define legal truth.

Only projectors may write to read-model tables.

## Consequences

- Read models must be rebuildable by replaying events.
- Query performance can be optimised without weakening the Register.
- Direct writes to projections are forbidden and should be covered by fitness
  functions.
