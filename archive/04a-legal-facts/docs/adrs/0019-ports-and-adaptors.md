# ADR-0019: Ports and Adaptors

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Domain logic that directly references infrastructure (Postgres, RabbitMQ, HTTP
clients, Stripe) is hard to test in isolation, couples the domain to specific
technology choices, and makes those choices difficult to change. The domain is
the most valuable and longest-lived part of the system; it must not carry
infrastructure debt.

## Decision

Define all external dependencies as ports: named protocols or interfaces that
describe what the domain needs, not how it is delivered. Adaptors implement
those ports for specific technologies. Domain code depends only on ports.
Adaptors are constructed and injected at the application boundary (system
start).

Examples:

| Port | Production Adaptor | Test Adaptor |
|------|-------------------|--------------|
| `event-store-port` | `postgres-event-store` | `in-memory-event-store` |
| `command-ledger-port` | `postgres-command-ledger` | `in-memory-command-ledger` |
| `payment-port` | `stripe-payment-adaptor` | `stub-payment-adaptor` |
| `address-validation-port` | `http-address-validation` | `stub-address-validation` |
| `notification-port` | `email-notification-adaptor` | `recording-notification-adaptor` |
| `identity-verification-port` | `oidc-identity-adaptor` | `stub-identity-adaptor` |

## Consequences

- Domain Deciders and process managers are pure and infrastructure-free. They
  can be tested with in-memory adaptors at Layer 1 speed.
- Technology choices (which database, which queue, which payment processor) are
  isolated to adaptors and can be swapped without touching domain logic.
- Port definitions are the specification for what each external system must
  provide. They are the contracts between domain and infrastructure.
- Additional indirection: every external dependency has two artefacts (port
  definition and adaptor implementation) instead of one. This is the cost of
  testability and flexibility.
- Adaptor tests (Layer 2) verify that the production adaptor correctly
  implements the port against a real infrastructure instance.
