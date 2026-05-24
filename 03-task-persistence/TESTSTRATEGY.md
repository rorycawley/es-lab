# Test Strategy: 03-task-persistence

## What do we test?

Compliance with two categories of requirement:

**Acceptance criteria** — the behaviours the system must exhibit. Each criterion becomes one or more tests. If there is no criterion, there is no test.

**Quality attributes** — non-functional requirements such as performance and security. These cannot be expressed as acceptance criteria for a single behaviour; they apply across the system and require their own test types.

---

## Types of test

### Unit test

Kent Beck's definition: a test that runs in isolation from other tests. Isolation means no shared state, no database, no network — not "one class tested alone." Replacing every collaborator with a double to achieve class isolation is the misreading that breaks TDD.

A unit test covers a use case, a story, or a scenario — not a method. Tests written against individual methods are fragile: they break on every internal change while failing to capture the behaviour that actually matters.

> *"Your API is your contract; your tests should test the API, not the implementation details."* — Ian Cooper

In this project, unit tests cover two boundaries:

**Domain behaviours** — handlers and components called directly, with ports stubbed. No I/O.

| Acceptance criterion | Test |
|---|---|
| submit rejects blank title or missing description with 422 | `commands_test.clj` |
| submit returns 201 with the saved record, reads `X-User-Id` | `commands_test.clj` |
| submit writes an audit event with the correct actor and action | `commands_test.clj` |
| list returns 200 with a `requests` array | `queries_test.clj` |
| search validates and trims the query; rejects blank with 422 | `queries_test.clj` |
| component transitions idle → loading → success/error | `app.component.spec.ts` |
| list reloads after a successful submit | `app.component.spec.ts` |

**Adapter behaviours** — the JDBC adapter tested against a real Postgres container (Testcontainers). Adapters have their own acceptance criteria: what must the adapter do to satisfy its port contract? Testing this is also unit testing — one adapter, one real dependency, no assembled stack.

| Acceptance criterion | Test |
|---|---|
| all migrations are applied and recorded in `schema_version` | `postgres_test.clj` |
| `save!` returns all fields with a UUID `request_id` | `postgres_test.clj` |
| `list-all` returns records ordered most-recent-first | `postgres_test.clj` |
| `search` ranks title matches above description matches | `postgres_test.clj` |
| `search` strips `rank` and `search_vector` from results | `postgres_test.clj` |
| `transact!` rolls back the service request when `audit/record!` fails | `postgres_test.clj` |
| `audit/record!` enforces the FK to `service_requests` | `postgres_test.clj` |

---

### Integration test

Kent Beck's definition: a test that exercises interactions between components where shared state, databases, or networks are involved. Integration tests are slower and cannot run in full isolation.

In this project, integration tests verify that the assembled backend (routes, middleware, JSON, ports) wires up correctly. They use an HTTP client against a live Jetty server backed by in-memory ports — no Docker required at this layer.

| What it proves | Tool |
|---|---|
| Routes, middleware, JSON serialisation, OpenAPI spec, Swagger UI | `babashka.http-client` → live Jetty |
| Submit-then-list roundtrip through the HTTP layer | `babashka.http-client` → live Jetty |

Integration tests confirm assembly. They do not re-prove acceptance criteria already covered by unit tests.

---

### Performance test

Driven by performance quality attributes, not acceptance criteria. Current thresholds: p95 < 1000 ms, p99 < 2000 ms, success rate > 99%.

Two Gatling scenarios run in parallel against the full stack: `submit-and-list` (10 users over 30 s) and `browse-and-search` (20 users over 30 s). Run with `bb test:perf`; the stack stays up so the HTML report in `perf/target/gatling/` can be inspected.

---

### Security test

Driven by security quality attributes. Partially covered by the acceptance tests.

Attack surface: arbitrary JSON bodies without size limits; `X-User-Id` header trusted without authentication.

QA-05-01 (dangerous inputs stored and returned unchanged) and QA-05-02 (error responses contain no stack traces, file paths, or credentials) are verified in `acceptance/app_test.clj` against the live stack.

Not yet covered: automated input fuzzing, oversized payload rejection, OWASP ZAP scan. No `bb test:security` task exists; these require a separate tool integration.

---

### End-to-end test

Drive the fully assembled system from the outside as a real client would. No handlers called directly; no ports bypassed.

| What it proves | Tool | Docker |
|---|---|---|
| nginx proxy, full submit-then-list-then-search roundtrip, persistence across Postgres restart | `babashka.http-client` → full stack | yes |
| Form submit and title appearing in the list in a real browser | Playwright (Chromium) | yes |

E2E tests are the most expensive and should be the fewest. They confirm that the system is assembled and reachable, not that the business rules are correct — rules are proved in unit tests.

---

### Contract test

A contract test verifies that a consumer and provider agree on an interface. The frontend is a consumer of the backend API; it has expectations about request and response shapes. A broken contract is silent — the backend changes a field name and the frontend breaks at runtime with no failing test.

*Not yet implemented.* Currently response shapes are verified implicitly inside integration and E2E tests. An explicit contract layer (e.g. Pact) would make the frontend's expectations a first-class artefact and catch breaking changes before the stack is assembled.

---

### Exploratory test

Write tests to explore unfamiliar code or a new design — "drilling tests" that help you understand the space. These are throwaway. Once the behaviour is clear and expressed in proper unit tests, delete the exploration tests. Keeping them couples the suite to decisions that were temporary.

---

## When do we change a test?

A test is a specification of a requirement. It changes only when the requirement changes.

**Change a test when:**
- An acceptance criterion is added, changed, or removed.
- A quality attribute threshold is revised (e.g. a tighter p95 target).
- A contract between consumer and provider is renegotiated.

**Do not change a test when:**
- You are refactoring. If a refactor breaks a test, the test was coupled to implementation, not to the requirement. Fix the test, not the refactor.
- You are adding a new class, extracting a helper, or renaming an internal. None of these change a criterion.

---

## What to mock — and what not to

**Mock ports and public APIs** — the boundaries between the domain and its collaborators. `ServiceRequestPort` and `AuditPort` are mocked in domain tests. This isolates the handler from the JDBC adapter and makes the test's intent explicit.

**Do not mock adapters.** An adapter implements a port; it must be tested against the real external system. Mocking the database cuts the test off from actual SQL semantics, column names, types, migration ordering, and constraint enforcement. Use Testcontainers.

**Do not mock external systems you do not own.** You cannot mock a system into behaving the way it actually will. Test correct usage through integration and contract tests against the real system or a real test instance.

**Do not mock internals.** Private functions, implementation details, and things that are not part of a public API are not test targets. If something feels hard to test without exposing an internal, that is a design signal — not a reason to make the internal public.

---

### Operational assertions

Some acceptance criteria describe the lifecycle of the stack itself rather than business behaviour. These cannot be expressed as HTTP or browser tests; they are verified as assertions inside `bb.edn` tasks.

| Acceptance criterion | Where enforced |
|---|---|
| AC-01-01: `docker compose up --wait` exits 0; postgres, backend, frontend healthy; migrate exited | `--wait` flag in `test:acceptance` and `test:e2e` tasks (exit-code guarantee); service states asserted in `compose-stack-is-healthy` acceptance test |
| AC-07-01: no running containers after `bb down` | `assert-no-running-containers!` called in `down`, `test:acceptance`, `test:e2e` |
| AC-07-02: project Postgres volume absent after `bb down` | `assert-no-project-volume!` called in `down` |
| AC-07-03: project Postgres volume absent after any Docker-backed test run | `assert-no-project-volume!` called in `test:acceptance`, `test:e2e` finally blocks |

Each assertion throws an exception if the condition is not met, failing the task in the same way a test failure would.

---

## Running

```sh
bb check             # static analysis: lint + format check (precondition)
bb test              # standard suite: backend + frontend + acceptance + E2E (excludes perf)
bb test:backend      # domain unit + adapter unit + HTTP integration
bb test:frontend     # frontend unit tests (Jest, no Docker)
bb test:acceptance   # HTTP acceptance (full stack, resets Postgres volume)
bb test:e2e          # browser E2E (full stack, resets Postgres volume)
bb test:perf         # performance (full stack, stays up after)
```
