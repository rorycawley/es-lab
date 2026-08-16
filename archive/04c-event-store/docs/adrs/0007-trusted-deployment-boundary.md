# ADR 0007: Trusted Deployment Boundary

- Status: Accepted
- Date: 2026-08-08

## Context

Authentication and authorization are outside this backend version. A cart UUID
or signed observation token proves neither identity nor permission.

## Decision

Deploy the backend only on a private listener or network reachable through a
trusted upstream component that authenticates callers and authorizes cart or
support operations. Direct untrusted ingress is prohibited.

Local configuration binds to loopback by default. A wildcard bind is rejected
unless `TRUSTED_UPSTREAM_ENFORCED=true` explicitly records that the deployment
provides the required upstream boundary.

## Consequences

- Deployment manifests and tests must prove there is no direct public route.
- Observation signing does not replace authentication or authorization.
- Adding in-service identity or permission rules requires new requirements and
  a new ADR.
