# Test Strategy

This project is intentionally shaped for testability:

- a pure domain core with no dependencies
- SQL/DDL and PL/pgSQL that can be exercised directly
- application use cases behind ports
- driven adapters, including memory and Postgres event stores
- driving adapters, currently the HTTP API
- a contract-first OpenAPI document
- local Compose and CI Testcontainers parity for Postgres plus Flyway

The strategy is to push evidence as low as possible, then keep a small number
of broader tests that prove wiring and real infrastructure behaviour.

## Sources

The strategy follows these external references:

- Martin Fowler's test pyramid: use many focused low-level tests and fewer
  broad-stack tests because broader tests cost more to run and maintain.
  https://martinfowler.com/bliki/TestPyramid.html
- Martin Fowler's microservice testing taxonomy: unit, integration, component,
  contract and end-to-end tests exercise different boundaries and should not be
  collapsed into one vague "integration" bucket.
  https://martinfowler.com/articles/microservice-testing/
- Google's small/medium/large test sizing: classify tests by what they touch
  rather than by overloaded names. Small tests do not use network or database;
  medium tests may use localhost, filesystem, threads or a database; large tests
  use more of the deployed system.
  https://testing.googleblog.com/2010/12/test-sizes.html
- OpenAPI Initiative documentation: request and response bodies are described
  with `content`, media types and schemas, so the HTTP contract should validate
  both accepted input and produced output.
  https://learn.openapis.org/specification/content.html
- Testcontainers documentation: tests can depend on real services in lightweight
  throwaway Docker containers rather than mocks or in-memory substitutes.
  https://docs.docker.com/testcontainers/
- Testcontainers Java lifecycle docs: containers should have explicit lifecycle
  control and be stopped by the test harness/resource reaper.
  https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/
- Flyway documentation: migrations are versioned, ordered, checksummed and
  tracked in schema history, so the migration path is part of the system under
  test.
  https://github.com/flyway/flywaydb.org/blob/gh-pages/documentation/concepts/migrations.md
- Flyway validate documentation: validation detects changed names, types and
  checksums against applied migrations.
  https://documentation.red-gate.com/flyway/reference/commands/validate
- PostgreSQL transaction isolation documentation: at read committed, PostgreSQL
  waits for concurrent updaters and re-evaluates the `WHERE` condition against
  the updated row, which is central to the optimistic concurrency SQL tests.
  https://www.postgresql.org/docs/17/transaction-iso.html

## Test Layers

| Layer | Size | What it proves | Current tests |
|---|---:|---|---|
| Domain core | Small | Pure business decisions, state transitions and invariants without IO | `test/cart/core_test.clj` |
| Serialization boundary | Small | Event JSONB shape is stable without Transit and survives generated cases | `test/cart/serialisation_test.clj` |
| Application/use cases | Small/Medium | Ports are used correctly, command retry semantics are correct, query stack is separate from HTTP | `test/cart/app/*_test.clj` |
| In-memory driven adapter | Medium | The fast adapter obeys the same event-store port contract as Postgres | `event_store_memory_test.clj`, `event_store_contract.clj` |
| SQL function | Large | `append_to_stream` itself is race-safe and returns conflict as data | `append_fn_test.clj` |
| Postgres driven adapter | Large | Clojure marshalling plus real Postgres preserve the event-store contract and race semantics | `event_store_postgres_test.clj` |
| HTTP driving adapter | Medium | HTTP goes through command/query ports, task routes are mounted, old routes are gone | `http_test.clj` |
| OpenAPI contract | Medium | Checked-in contract is served and representative requests/responses conform to schemas | `http_test.clj` |
| HTTP performance smoke | Medium | Task/query HTTP paths stay inside coarse latency budgets without Docker | `http_perf_test.clj` |
| Component lifecycle | Medium | Jetty, datasource and handler lifecycle wiring starts and stops cleanly | `system_test.clj` |
| Full-stack HTTP + Postgres | Large | Jetty, command/query use cases, runtime DB role and migrated Postgres work together | `http_postgres_test.clj` |
| Manual local system | Large | Compose Postgres, one-shot Flyway and the API work together for exploration | `bb up`, `bb down` |

Unicode and internationalization boundary coverage is split across these
layers: the serialization tests prove JSON round trips, the HTTP tests prove
UTF-8 request/response handling, the Postgres tests prove UTF8 server/client
encoding plus `text`/`jsonb` persistence, and the full-stack smoke proves the
same path through Jetty and the runtime database role. These tests use English,
Chinese and Arabic text as user data.

## Gates

### Inner Loop

Use the smallest task that covers the code being changed:

| Change | Run |
|---|---|
| Domain rules | `bb test:core` |
| Use case, retry, command/query stack | `bb test:app` |
| In-memory event store | `bb test:memory` |
| HTTP adapter or OpenAPI contract | `bb test:http` |
| HTTP response-time regression | `bb test:perf` |
| Migration SQL, PL/pgSQL, Postgres adapter, full-stack HTTP + Postgres | `bb test:postgres` |

### Before Commit

Run:

```bash
bb precommit
```

`bb precommit` is the no-Docker local gate. It runs:

- `bb check`
- `bb test:core`
- `bb test:app`
- `bb test:memory`
- `bb test:http`

It deliberately does not run `bb test:perf`. Performance smoke tests are useful
as CI regression guards, but they should not slow every commit attempt.

If a commit changes any of these paths, also run the Docker-backed Postgres
suite before committing:

- `resources/db/migration/**`
- `resources/docker/postgres/**`
- `compose.yaml`
- `src/cart/adapter/driven/event_store_postgres.clj`
- `test/cart/adapter/driven/append_fn_test.clj`
- `test/cart/adapter/driven/event_store_postgres_test.clj`
- `test/cart/acceptance/http_postgres_test.clj`
- `test/cart/test_db.clj`

Command:

```bash
bb test:postgres
```

### Pull Request / CI Gate

CI must run:

```bash
bb ci
```

`bb ci` runs `bb check` and the full `bb test` suite, including Postgres 18.4
and Flyway 13.0.0 through Testcontainers. It also runs the HTTP performance
smoke suite.

This should be the required PR gate before merge. It is deliberately stricter
than `bb precommit` because CI has a known Docker environment and should prove
the real migration and database behaviour.

### Release Gate

Before a release tag:

```bash
bb ci
bb up
# run a short smoke against /health, /openapi.json, one command and one query
# Ctrl-C the service
bb down
```

The release smoke is not a substitute for CI. It verifies the same shape a
developer or operator will run locally: Compose Postgres, one-shot Flyway, and
the app role `cart_app`.

## Contract-First API Testing

The OpenAPI document at `resources/openapi/cart-api.openapi.json` is the source
of truth.

The HTTP test suite must keep proving:

- `/openapi.json` serves the checked-in file verbatim
- all command/query routes in the OpenAPI document are mounted
- command and query operations are POST-only
- legacy resource-shaped routes remain absent
- representative valid request bodies conform to the request schemas
- representative live responses conform to the response schemas
- rejected requests return bodies that conform to error schemas
- English, Chinese and Arabic JSON text is decoded and encoded as UTF-8

The current validator is intentionally test-scoped and supports the JSON Schema
features this contract uses. If the contract becomes more complex, replace or
supplement it with a dedicated OpenAPI validator rather than growing a large
home-grown validator.

For CI governance, add these later when the API has consumers:

- OpenAPI linting, for example Spectral
- breaking-change detection, for example `oasdiff`, against the last released
  contract
- generated examples or mock server validation from the OpenAPI file

## HTTP Performance Testing

Fast user response time is a product requirement, so CI includes a performance
smoke test for the HTTP task/query paths.

The current test is intentionally narrow:

- it calls the Ring handler in-process
- it uses the in-memory event store
- it includes JSON parsing, validation, routing, use-case dispatch and response
  encoding
- it excludes Jetty, TLS, network hops, kernel scheduling and Postgres latency

This makes it a useful regression guard for accidental HTTP adapter overhead,
not a load test. Default budgets are intentionally coarse:

- `POST /queries/get-cart` p95 under `10ms`
- `POST /commands/add-product-item` p95 under `15ms`
- both p99 values under `75ms`

Override those budgets on slower or stricter runners with:

```bash
HTTP_PERF_QUERY_P95_MS=20 HTTP_PERF_COMMAND_P95_MS=30 HTTP_PERF_P99_MS=100 bb test:perf
```

Treat a failure as a signal to inspect recent route, JSON, validation or use-case
changes. Do not hide a real regression by raising thresholds without explaining
why in the commit.

## Database and Concurrency Testing

The database tests are not generic "repository tests"; they are the proof of
the central guarantee: two requests acting on the same stream version cannot
both write.

The SQL function tests exercise `append_to_stream` directly. They must keep
asserting:

- exactly one concurrent expected-version append wins
- the loser returns conflict data, not an exception
- losing writes do not leave rows behind
- stream positions remain contiguous
- multi-event appends reserve a contiguous range
- `:any` semantics do not manufacture conflicts
- create races use `ON CONFLICT` behaviour rather than unique violations

The Postgres adapter tests then prove the same semantics through the Clojure
port and JSONB marshalling.

Migrations are tested by starting Postgres, then running Flyway in a separate
one-shot container before any Postgres tests run. That mirrors local Compose
and production deployment more closely than in-process migration calls.

The full-stack HTTP + Postgres smoke test is deliberately thin. It does not
duplicate the adapter contract or OpenAPI contract tests; it proves that a real
Jetty server can write through the command HTTP path as `cart_app`, persist into
migrated Postgres, stop, restart, and read the same cart through the query HTTP
path.

## Isolation and Cleanup Rules

- Small and medium tests must not depend on test order.
- Docker-backed tests must own their infrastructure through Testcontainers.
- Local exploratory runs use `bb up` and must be followed by `bb down`.
- CI must not use Testcontainers reusable containers.
- A failed test must leave enough diagnostic data in the assertion to show
  whether the failure is in core, SQL, marshalling, HTTP mapping or wiring.

## Current Gaps

These are useful next hardening steps, not blockers for the current state:

- Add a GitHub Actions workflow that runs `bb ci` on pull requests.
- Add OpenAPI linting and breaking-change checks once a baseline contract is
  published.
- Add load tests with real Jetty and Postgres once target latency/SLO numbers
  exist. The current performance smoke test catches local adapter regressions;
  it does not prove production capacity.
- Make the race iteration count configurable so nightly builds can run a larger
  stress profile than pull requests.
