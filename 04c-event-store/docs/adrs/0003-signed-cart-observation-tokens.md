# ADR 0003: Signed Cart Observation Tokens

- Status: Accepted
- Date: 2026-08-08

## Context

Actors need to prove which cart revision informed a command without being able
to invent a stream revision. Raw event-store versions would expose persistence
semantics and an unsigned value could be fabricated or moved between carts.

## Decision

Represent an observation with an opaque, versioned HMAC-signed token containing
the cart UUID, stream revision, token version and signing-key identifier. Verify
authenticity and cart binding during input validation. Compare the decoded cart
and revision, never token bytes, when checking currency.

Tokens have no time-based expiry. Retain verification keys for every token that
may still be presented. Key removal is an exceptional invalidation operation.
The signature supplies authenticity and integrity, not confidentiality or
authorization.

## Consequences

- Altered, fabricated and wrong-cart tokens are invalid input.
- Authentic older revisions are concurrent-change conflicts.
- Multiple token strings can represent one current observation.
- Accepted-command replay must return the stored original marker.
