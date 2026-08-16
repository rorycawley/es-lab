# ADR 0002: Event Sourcing and Atomic Projections

- Status: Accepted
- Date: 2026-08-08

## Context

The requirements need complete accepted change history, deterministic cart
state reconstruction and immediate visibility to queries after command success.

## Decision

Use immutable cart domain events as the source of truth. Fold a cart stream to
obtain command state. Pure projectors derive the cart-view and history models
from accepted events. Append events, update both projections and store the
accepted command result in one local database transaction.

Domain events remain internal. This version has no broker, outbox, public event
contract or external subscriber.

## Consequences

- Rejected, invalid and conflicting attempts append no event and leave no
  history entry.
- Queries have read-after-write consistency without folding event streams.
- Event schemas are immutable; changes require a new version and pure upcaster.
- Cross-service eventual consistency is outside this version.
