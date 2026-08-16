# 04a-legal-facts

A production-quality micro companies registry built on the [Companies Registration Act](docs/00-companies-registration-act.md). This is the full implementation successor to `04-legal-facts`, covering the complete lifecycle from draft application through examination, decision, registration, and post-registration obligations.

## Documentation

Read these in order before touching the code:

| # | Document | Purpose |
|---|----------|---------|
| 1 | [00-companies-registration-act.md](docs/00-companies-registration-act.md) | The law. All rules and stories trace here. |
| 2 | [04-domain-discovery.md](docs/04-domain-discovery.md) | Ubiquitous language. Code uses these names. |
| 3 | [07-business-rules.md](docs/07-business-rules.md) | FSMs and business rules that drive the Deciders. |
| 4 | [08-user-stories.md](docs/08-user-stories.md) | Acceptance criteria. Each maps to a test. |
| 5 | [09-testing-strategy.md](docs/09-testing-strategy.md) | Five test layers. Read before writing any test. |
| 6 | [DEVELOPMENT-WORKFLOW.md](docs/DEVELOPMENT-WORKFLOW.md) | TDD loop. Red → Green → Refactor → CI. |
| 7 | [FIRSTSTEPS.md](FIRSTSTEPS.md) | How to get from zero to a first passing test. |

## Quick start

```bash
bb install       # install tools via mise
bb db:up         # start Postgres and apply migrations
bb test:backend  # run all tests (Layer 1 requires no Docker)
bb up            # build and start the full stack
bb down          # stop and remove all containers and volumes
```

After `bb up`:

| URL | Purpose |
|-----|---------|
| `http://localhost:8080/health` | Health check |
| `http://localhost:8080/openapi.json` | OpenAPI 3.0 spec |
| `http://localhost:8080/swagger-ui` | Interactive API browser |

## Architecture

Event-sourced backend. The event store is the legal Register — a company exists in law because a `RegisteredCompanyCreated` event exists in the store. Projections are derived, disposable, and rebuildable from events.

```
HTTP request
  → Command handler (auth, idempotency, load aggregate events)
    → Decider (pure function: state + command → event | error)
      → Event store (append event, enforce write-side invariants)
        → Projector (update read models asynchronously)
```

Key aggregates and their Deciders:

| Aggregate | Decider | Business rules |
|-----------|---------|----------------|
| Draft | `registry.draft.decider` | BR-DR-*, BR-SB-* |
| Registration Application | `registry.registration.decider` | BR-RA-*, BR-RQ-*, BR-AP-* |
| Registered Company | `registry.company.decider` | BR-RC-*, BR-RG-* |

## Test layers

| Layer | Scope | Speed | Gate |
|-------|-------|-------|------|
| 1 — Pure function | Deciders, FSMs | < 2s | pre-commit |
| 2 — Adapter | Postgres adapters via Testcontainers | < 45s | pre-push |
| 3 — Behavioural | Full HTTP stack via Testcontainers | < 3 min | CI |
| 4 — Fitness functions | Architectural characteristics | < 5 min | CI |
| 5 — Contract | API consumer contracts | < 2 min | CI |

Test names reference acceptance criterion IDs directly: `AC-AP-002-001`, `FF-003`, etc.

## API

Base path: `/api/v1`

| Method | Path | Story | Description |
|--------|------|-------|-------------|
| `POST` | `/company-registration-drafts` | US-AP-002 | Create a new draft |
| `POST` | `/company-registration-drafts/:id/cancel` | US-AP-004 | Cancel a draft |
| `POST` | `/company-registration-drafts/:id/submit` | US-AP-007 | Submit a draft |
| `GET`  | `/company-registration-drafts` | US-AP-005 | List active drafts |
| `GET`  | `/company-registration-drafts/:id` | US-AP-006 | View a draft |
