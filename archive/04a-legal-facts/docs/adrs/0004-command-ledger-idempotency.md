# ADR-0004: Command Ledger For Idempotency

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Commands may be retried by browsers, process managers, schedulers, or
infrastructure. Retrying must not duplicate payment, event appends,
notifications, or registered-company creation.

Earlier designs sometimes try to make event `causation_id` globally unique,
but one command can legitimately cause multiple events.

## Decision

Command idempotency is enforced by a command ledger keyed by command ID.

`causation_id` and `correlation_id` are traceability metadata. They are not
global uniqueness constraints for event identity.

## Consequences

- Repeated commands return the previously recorded outcome.
- Multiple events may share one causation ID.
- Idempotency is explicit and testable.
- Consumers still need their own deduplication where they perform side effects.
