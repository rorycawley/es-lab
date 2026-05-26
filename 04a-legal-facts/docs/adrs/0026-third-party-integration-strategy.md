# ADR-0026: Third-Party Integration Strategy

*Status: Accepted.*
*Date: 2026-05-26.*


## Context

When integrating with third-party systems — payment gateway (Stripe), identity
broker (OIDC), address validation, person verification registry, email delivery
— two bodies of patterns are commonly recommended:

- The **Anti-Corruption Layer** (ACL), from Eric Evans's *Domain-Driven Design*:
  protect the domain model from being polluted by an external system's
  concepts, language, and failure modes.

- **Enterprise Integration Patterns** (EIP), from Hohpe and Woolf: reliable
  message delivery, routing, transformation, guaranteed delivery, dead-letter
  handling, competing consumers.

These appear to be alternatives. They are not. They answer different questions
and operate at different levels. This ADR records how each applies in this
system.


## Decision

### The Anti-Corruption Layer is implemented by the adapter (ADR-0019)

The ACL is not a separate architectural layer on top of Ports and Adaptors — it
*is* the adapter. The port defines what the domain needs, expressed entirely in
the domain's own language and types. The adapter translates between the domain's
language and the external system's language.

| Domain port (ACL boundary) | External system's model |
|---------------------------|------------------------|
| `(authorize-payment {:amount …})` → `PaymentAuthorised` event | Stripe `charge` object, HTTP 402, card-decline codes |
| `(verify-identity {:person-id …})` → `IdentityVerified` | OIDC `id_token`, JWT claims, bearer flow |
| `(validate-address {:address …})` → `AddressValid` | Third-party address API response envelope |
| `(send-notification {:to …})` → fire-and-forget | Email provider API, delivery receipts |

The domain Decider never sees a Stripe error, a JWT claim, or an HTTP status
code. The adapter absorbs, translates, and surfaces only what the port contract
defines. This is the ACL in full effect.

**Consequence:** every third-party integration in this system has exactly one
ACL boundary — the adapter that implements its port. No additional translation
layer is needed.


### EIP patterns are adopted selectively, where the specific problem arises

EIP is a catalogue of ~65 patterns for integrating systems via messaging. The
patterns do not prescribe a full messaging infrastructure; they name and solve
specific problems. This system adopts the following:

| EIP Pattern | Problem it solves | Where used | ADR |
|-------------|------------------|-----------|-----|
| **Transactional Outbox** (Store and Forward) | Event persistence and external delivery must be atomic | External notification delivery, future broker integration | ADR-0005 |
| **Polling Consumer** | Consumers must process events in order without missing any, across multiple instances | Projectors, process managers reading from the event store | ADR-0013, ADR-0015 |
| **Competing Consumers** | Multiple instances must not double-process the same event | Advisory lock per consumer — only one instance polls at a time | ADR-0013 |
| **Process Manager** (Saga) | Multi-step cross-aggregate workflows must recover from partial failure | `ApplicationApprovalWorkflow` coordinating approval and company creation | ADR-0006 |
| **Idempotent Receiver** | At-least-once delivery must not cause duplicate side-effects | All event consumers; command ledger for commands | ADR-0004 |
| **Dead Letter Channel** | Failed deliveries must not block the queue and must be inspectable | Outbox publisher — failed records are parked and alerted | ADR-0005 |


### Full EIP messaging topology is not adopted

A full EIP messaging topology — a broker (RabbitMQ, Kafka) as the backbone for
all integration, with Publish-Subscribe Channels, Message Routers, and
Aggregators wiring modules together — is not used for internal integration.

**Reason:** The modular monolith (ADR-0012) with event store polling (ADR-0015)
provides equivalent guarantees with significantly less operational complexity.
Internal consumers (projectors, process managers) read directly from the event
store; they do not need a broker to receive events. A broker introduces an
additional failure domain, a deployment dependency, and a new class of partial
failure (event persisted but not published) that the transactional outbox
already solves for external systems.

EIP patterns are applied where the problem they solve exists. They are not
applied as a blanket integration infrastructure.


## Consequences

- Every third-party call crosses a port boundary. Domain code never imports an
  SDK, an HTTP client, or an external type directly.
- Adding a new third-party system means defining a port and writing an adapter.
  The domain is unaffected.
- The EIP patterns in use (outbox, polling consumer, process manager,
  idempotent receiver) are already implemented. No new EIP infrastructure is
  needed unless a new delivery problem arises that these do not cover.
- If a future requirement introduces genuinely broker-scale messaging (fan-out
  to thousands of consumers, event streaming across organisational boundaries),
  a new ADR is required. That ADR must justify the operational cost and must not
  change the port contracts that domain code depends on.
- The Ports and Adaptors pattern (ADR-0019) and this ADR together answer every
  question about how an external system is integrated. No further integration
  strategy document is needed.
