# ADR-0001: Event-Sourced Register As Source Of Truth

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The Register is the authoritative legal record of registered companies. A
company must not exist merely because a form was submitted, an application was
approved, or a row exists in a query table.

## Decision

The authoritative Register is the event-backed set of `RegisteredCompany`
streams containing `RegisteredCompanyCreated`.

`RegisteredCompanyCreated` is the legal existence fact. Events in the Register
are append-only and must not be altered or deleted.

## Consequences

- Legal existence is rebuilt from events, not inferred from projections.
- Read models can be destroyed and rebuilt without changing legal truth.
- Tests must prove that no company exists without `RegisteredCompanyCreated`.
- Operational repair must record new facts rather than mutate old ones.
