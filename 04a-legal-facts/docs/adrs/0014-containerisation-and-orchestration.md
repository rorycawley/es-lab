# ADR-0014: Containerisation and Container Orchestration

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The system needs to be deployable in a reproducible, environment-agnostic way.
Local development, CI, and production must run the same artifacts. The
deployment topology (number of containers, orchestrator choice) is deferred in
ADR-0009, but the decision to containerise is not.

## Decision

Package every deployment unit as an OCI container image. Use Docker Compose
for local development and CI. Production orchestration (Kubernetes or
equivalent) will be specified when ADR-0009 is resolved.

Images are built from a reproducible build process. No host-local dependencies
outside the container image. The same image is promoted through environments
without rebuilding.

## Consequences

- Local development matches CI matches production topology in all respects
  that matter: same OS, same runtime, same environment variables.
- Container images are the deployment artifact. No separate packaging step.
- Testcontainers (used in Layer 2 tests) uses the same Docker daemon and
  benefits from the same image cache.
- Infrastructure-as-code for the container configuration lives in the
  repository alongside the application code.
- Adds a container registry dependency and an image build step to CI.
- Developers must have a container runtime available locally (Rancher Desktop
  or Docker Desktop). See setup documentation for Testcontainers configuration.
