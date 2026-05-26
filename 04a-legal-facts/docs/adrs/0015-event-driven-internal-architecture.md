# ADR-0015: Event-Driven Internal Architecture

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Deciders produce events. Multiple components need to react to those events:
projectors update read models, process managers advance workflows, the outbox
publisher queues events for external consumers, and the audit log writer
records decisions. Hardcoding these dependencies would tightly couple producers
to consumers and make it difficult to add new consumers.

## Decision

Use an internal event bus to decouple event producers from event consumers
within the modular monolith. When a Decider produces events, they are
dispatched to all registered consumers via the bus. The bus is in-process and
synchronous within a transaction boundary unless a consumer explicitly opts
into async delivery.

External publication (to message brokers consumed by systems outside this
process) uses the transactional outbox (ADR-0005) - not the internal bus.
The outbox publisher is itself a consumer on the internal bus.

## Consequences

- New event consumers (projectors, audit log writers, notification triggers)
  can be added without modifying the Decider or the command handler.
- The boundary between internal event dispatch and external publication is
  explicit: internal bus for in-process reactions, outbox for everything
  outside the process.
- Consumers that fail must not silently swallow errors; the bus must propagate
  failures or write them to a dead-letter mechanism.
- Synchronous in-transaction dispatch keeps consistency simple: if a projector
  fails, the transaction rolls back. Async consumers must be idempotent.
- Internal event schema is not a public contract. It may carry implementation
  fields that are not published externally.
