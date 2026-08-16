# ADR-0016: Task-Based Endpoints for API Commands

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

A generic resource-oriented REST API (e.g., `PUT /applications/{id}` with a
partial body) cannot express explicit user intent. It makes it impossible to
map an HTTP request directly to a domain command, enforce state-specific
business rules at the endpoint boundary, or record meaningful audit entries.
A `PUT` that changes status from `submitted` to `withdrawn` looks identical at
the HTTP level to any other `PUT`.

## Decision

Design API endpoints as task-based commands that express explicit user intent.
Each command maps to a dedicated endpoint:

- `POST /drafts/{id}/cancel` - not `PUT /drafts/{id}` with `{status: cancelled}`
- `POST /applications/{id}/withdraw` - not `PATCH /applications/{id}`
- `POST /applications/{id}/begin-examination` - not `PUT /applications/{id}`

Endpoints accept the minimal payload needed for that command and nothing more.

## Consequences

- Every HTTP endpoint maps directly to exactly one domain command. The API
  surface is self-documenting.
- Business rules and FSM checks fire at the command boundary with clear error
  messages attributable to the specific command.
- Audit log entries record intent ("application withdrawn by applicant") not
  field changes ("status changed from X to Y").
- API surface has more endpoints than a resource-oriented API. This is
  deliberate: explicit endpoints over implicit field mutations.
- Clients must know which task to invoke, not which fields to update. This fits
  a task-based UI where buttons and actions map to commands.
- CQRS (ADR-0011) makes this natural: commands are already first-class
  objects; task-based endpoints are their HTTP representation.
