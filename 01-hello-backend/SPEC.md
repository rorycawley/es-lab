# Spec: 01-hello-backend

## Story 1 — Zero-dependency setup

> As a developer, I want to run the backend locally without installing any language runtimes or build tools on my machine, so that I can get it running regardless of my existing environment.

**Acceptance criteria:**
- Given a machine with only Docker installed, when I start the backend, then it runs successfully without any additional installation steps

## Story 2 — Liveness verification

> As an operator, I want to confirm that the backend is running and healthy, so that I know a deployment succeeded and can detect when it fails.

**Acceptance criteria:**
- Given the backend is running, when I check its health, then I receive a response confirming its status and version
- Given the backend has just started, when a health probe checks it, then it reports healthy within 30 seconds

## Story 3 — Clean shutdown

> As a developer, I want to stop the backend cleanly, so that no orphaned processes are left consuming resources.

**Acceptance criteria:**
- Given the backend is running, when I stop it, then all processes terminate and no containers remain running

## Story 4 — API contract discoverability

> As a developer integrating with this service, I want to retrieve a machine-readable description of the API, so that I can understand what endpoints exist and what they accept and return without reading the source code.

**Acceptance criteria:**
- Given the backend is running, when I request the API spec, then I receive a valid OpenAPI 3.1.0 JSON document
- Given the backend is running, when I request the API spec, then the document describes the `/health` endpoint

## Story 5 — API browsability

> As a developer, I want to browse the API interactively in a browser, so that I can explore and test endpoints without writing any client code.

**Acceptance criteria:**
- Given the backend is running, when I navigate to the API browser, then I receive a successful response
