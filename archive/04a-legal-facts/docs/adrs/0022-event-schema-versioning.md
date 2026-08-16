# ADR-0022: Event Schema Versioning

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Events are stored permanently (ADR-0001, BR-AU-001 to BR-AU-003). As the
system evolves over years, event schemas will need to change: fields are added,
renamed, or restructured; new event types replace old ones. An event written
today must be readable and correctly interpreted five or ten years from now. In-
place schema migration of the event store is not acceptable: it would alter the
immutable historical record.

## Decision

Version event types using an explicit version suffix in the event type keyword:
`:registration-application-created` is the current version;
`:registration-application-created.v2` is its successor if a breaking schema
change is needed.

When a breaking change is required:
1. Introduce a new versioned event type (e.g. `.v2`).
2. Write an evolver (upcaster) function that transforms the old version to the
   new schema at read time in the `evolve` multimethod.
3. New events are written in the new schema. Old events remain unchanged.
4. Evolvers are tested as first-class components - not optional coverage.

Non-breaking additions (new optional fields) may be made to the current version
without introducing a new version suffix, provided the `evolve` multimethod
handles missing fields gracefully.

## Consequences

- Events remain readable regardless of how many schema generations have elapsed.
- The `evolve` multimethod must handle all known event versions; gaps are a
  bug.
- Evolvers add a processing step at event replay time. Performance impact is
  negligible for most schemas but must be considered for high-volume streams.
- Schema evolution history is visible in the codebase: evolver chains document
  every breaking change made to a schema.
- Old event type keywords are never deleted from the codebase while there are
  events of that type in the event store - which is forever.
- A schema registry or event catalogue may be introduced in a later ADR to
  formalise event contracts for external consumers.
