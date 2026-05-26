# ADR-0018: Separate Audit Log Store

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Registries are regularly audited by external auditors who must verify the
history of decisions and actions: who approved what, when, on whose authority,
and with what outcome. The event store is the legal source of truth, but its
structure is optimised for event sourcing - streams of typed events per
aggregate. An auditor querying the event store must understand aggregate IDs,
event types, causation chains, and stream structure. This is not a reasonable
expectation for an external audit.

## Decision

Maintain a separate audit log store that receives a denormalised,
human-readable record of every significant registry action. The audit log
captures: actor identity, role, action name, resource identifier, outcome,
timestamp, causation ID, and correlation ID. It is written for human
inspectability, not for event replay.

The audit log is written transactionally with the event store using the
transactional outbox (ADR-0005). The event store remains the legal source of
truth; the audit log is a derived, queryable view of institutional decisions.

The audit log is a separate schema/table from projections and from the event
store. It is append-only. No audit record may ever be deleted or altered.

## Consequences

- External auditors can query audit history with standard SQL without
  understanding the event sourcing model.
- The audit log schema can be designed for reporting: indexed by actor, by
  date range, by action type, by company.
- Dual-write risk (event store and audit log diverging) is mitigated by the
  outbox pattern: the audit record is queued atomically with the event, and
  delivered at-least-once by the outbox publisher.
- Additional storage. The audit log duplicates some event information in a
  flatter, more readable format. This is the purpose of the duplication.
- The audit log writer is an event consumer on the internal bus (ADR-0015).
  It receives domain events and transforms them into audit records.
- Audit log entries are not a substitute for the event store. The event store
  is the legal record; the audit log is a convenience for institutional
  oversight.
