# ADR-0021: Versioned Web API

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The API is the contract between the registry backend and its clients (frontend
applications, integration consumers). As the system evolves, request and
response shapes will need to change. Without versioning, any breaking change
silently breaks clients that have not been updated.

## Decision

Version the API using a URL path prefix: `/v1/`, `/v2/`, etc. A new version
is introduced only when a breaking change is unavoidable. Non-breaking additions
(new optional fields, new endpoints) do not require a new version.

A version is considered breaking when: a required request field is removed or
renamed, a response field consumers depend on is removed or renamed, a
semantics change alters behaviour that consumers observe, or a new required
request field is added.

Old versions are maintained for a defined deprecation period (to be specified
per release). Consumer contract tests (Testing Strategy, Layer 5) must pass for
every active version.

## Consequences

- Clients are protected from unannounced breaking changes.
- Multiple versions can coexist during migration, allowing gradual client
  upgrades.
- Each API version is independently tested with consumer contract tests.
- Route handlers may share domain logic but must maintain distinct request and
  response shapes per version.
- Discipline is required not to increment the version number for non-breaking
  changes — premature versioning fragments the API unnecessarily.
- API version deprecation must be communicated to clients and enforced with
  sunset headers before old versions are removed.
