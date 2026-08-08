# Test Strategy

This project is intentionally shaped for testability:

- a pure domain core with no dependencies
- Postgres SQL/DDL and PL/pgSQL that can be exercised directly
- SQLite DDL and transaction behaviour exercised through the adapter
- application use cases behind ports
- driven adapters, including memory, SQLite and Postgres event stores
- driving adapters, currently the HTTP API
- a contract-first OpenAPI document
- local Compose and CI Testcontainers parity for Postgres plus Flyway
- embedded SQLite migration and concurrency coverage without Docker

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
- Ian Cooper's "TDD, where did it all go wrong" framing: TDD developer tests
  should be driven by behavior, exercise a module's stable public API, and avoid
  coupling tests to private implementation details.
  https://www.youtube.com/watch?v=EZ05e7EMOLM
- Ian Cooper's "Microservices, where did it all go wrong" framing: modules are
  named code collections, components are replaceable modules, and services are
  components running in their own process. Replaceability, not size or container
  count, is the boundary pressure.
  https://www.youtube.com/watch?v=j2AQ9eTZ3-0
- Ian Cooper's "Implementing the Clean Architecture in .NET Core" framing:
  ports are purposeful use-case conversations at the application boundary;
  driving adapters call inbound command/query ports, while application code calls
  outbound ports for infrastructure dependencies.
  https://www.youtube.com/watch?v=IAcxetnsiCQ
- Google's small/medium/large test sizing: classify tests by what they touch
  rather than by overloaded names. Small tests do not use network or database;
  medium tests may use localhost, filesystem, threads or a database; large tests
  use more of the deployed system.
  https://testing.googleblog.com/2010/12/test-sizes.html
- OpenAPI Initiative documentation: request and response bodies are described
  with `content`, media types and schemas, so the HTTP contract should validate
  both accepted input and produced output.
  https://learn.openapis.org/specification/content.html
- Atlassian OpenAPI Request Validator: validates HTTP request/response
  interactions against an OpenAPI / Swagger specification.
  https://central.sonatype.com/artifact/com.atlassian.oai/openapi-request-validator
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
- SQLite transaction documentation: `BEGIN IMMEDIATE` starts a write
  transaction immediately, which is how the SQLite adapter avoids a deferred
  read-then-write race.
  https://www.sqlite.org/lang_transaction.html
- SQLite WAL documentation: WAL allows readers and a writer to proceed
  concurrently while still allowing only one writer at a time.
  https://www.sqlite.org/wal.html
- Flyway SQLite documentation: SQLite migrations are supported, with caveats
  such as no concurrent migration.
  https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite

## Naming and SUT Policy

This project avoids using "unit test" to mean "one class or one private
function". It also avoids using "unit test" for HTTP tests just because they run
without a real network. The name is reserved for behavior tests against the
application component contract: the inbound ports that driving adapters call.

The primary unit/developer tests are:

- Command behavior through `cart.port.cart-command`.
- Query behavior through `cart.port.cart-query`.
- Integration-event behavior through an inbound integration-event port when one
  exists.

Seen hexagonally, these tests are driving adapters too: executable consumers of
the application ports, just like HTTP or a future CLI. Their job is to express
the business conversation directly at the port boundary, with less transport
machinery than production adapters.

Other important tests have their own names:

- Domain business logic is tested directly through pure public functions such as
  `cart.core/decide` and `cart.core/fold`.
- Event-store behavior is tested through the outbound `EventStore` port
  contract, once per implementation.
- HTTP behavior is tested through the Ring handler and the OpenAPI contract as a
  driving-adapter contract test, not as a unit test.

In this repository, a unit/developer test means a behavior test for the
application component through an inbound port. A component is a replaceable
module: its implementation may change or be swapped as long as the public
contract still holds. Command/query behavior is therefore tested through ports,
not through concrete records, private helpers, Ring handlers or Jetty.

Test code may also act as a harness around outbound ports. The shared
`EventStore` contract suite is a test adapter that drives each persistence
adapter through the same outbound port contract, proving replaceability without
binding the application tests to Postgres, SQLite or memory internals.

For inbound behavior, a port is a use-case boundary: the public command, query
or integration-event conversation that an outside adapter is allowed to have
with the application. That maps to the BCE controller/interactor role without
coupling the system to a web framework. For outbound behavior, a port is the
dependency-inversion contract the use case needs from infrastructure. HTTP
tests should therefore prove request/response mapping, OpenAPI conformance and
dispatch into the command/query ports; they should not duplicate the behavior
suite already owned by those ports.

Services are components deployed in separate processes. Whether a component is
currently in-process, a macro service, or a future independently deployed
service is a process/physical architecture choice, not a reason to change the
business behavior tests. The tests should keep pressure on stable contracts and
replaceability so deployment shape can change later without rewriting the
behavior suite.

The unit of isolation is the test: tests must not leak state into each other.
The system under test should be the smallest public API that expresses the
behavior under discussion, not an internal helper introduced during refactoring.

Private helpers, transaction scripts and records are allowed to change without
new tests when the observable behavior is unchanged. Exceptions are deliberate
executable contracts at infrastructure boundaries, such as the Postgres
`append_to_stream` function and database DDL constraints. Those are tested
directly because they are the database adapter's owned public surface, not
incidental Clojure implementation detail.

Domain business logic deserves its own explicit developer-test category because
the functional core is pure. Aggregate invariants such as "a closed cart cannot
change", "a cart cannot remove more than it holds", and "an empty cart cannot be
confirmed" should be tested directly through pure public functions like
`decide` and `fold`. That gives fast, isolated evidence for each business
behavior without mocking adapters or exposing private helpers.

Terminology in this repository:

| Name | Meaning here |
|---|---|
| Business-logic test | Fast pure test for domain rules and aggregate invariants. |
| Unit/developer test | Fast behavior test for the application component through inbound command/query/integration-event ports. |
| Outbound port contract test | A reusable behavior suite that every implementation of an outbound port must satisfy. |
| Adapter contract test | A test proving a driving adapter, such as HTTP, honours its external contract and dispatches to ports. |
| Adapter integration test | A test proving a concrete driven adapter uses an external technology correctly. |
| Component/system smoke | A thin real-stack test proving wiring across components. |
| E2E test | Reserved for tests through a deployed application boundary. None currently. |
| Acceptance test | Reserved for executable acceptance criteria. The current HTTP+DB smokes are not acceptance tests. |

## Test Layers

| Layer | Size | What it proves | Current tests |
|---|---:|---|---|
| Business logic / aggregate invariants | Small | Pure business decisions, state transitions and invariants without IO | `bb test:business-logic`, `test/cart/core_test.clj` |
| Serialisation boundary | Small | Event database JSON shape is stable without Transit and survives generated cases | `bb test:serialisation`, `test/cart/serialisation_test.clj` |
| Primary unit: inbound ports | Small/Medium | Command/query behavior through the application component contract; future integration-event behavior belongs here too | `bb test:unit`, `test/cart/app/command_test.clj`, `test/cart/app/query_test.clj` |
| Outbound port contract: memory | Medium | The fast adapter obeys the same event-store port contract as persistent stores | `bb test:outbound-memory`, `event_store_memory_test.clj`, `event_store_contract.clj` |
| Outbound port contract: SQLite | Medium | Embedded SQLite migration, JSON text storage, DDL checks and write serialization obey the event-store port contract | `bb test:outbound-sqlite`, `event_store_sqlite_test.clj` |
| Outbound adapter integration: Postgres | Large | `append_to_stream`, DDL, Clojure marshalling and JSONB preserve the event-store contract and race semantics | `bb test:outbound-postgres`, `append_fn_test.clj`, `event_store_postgres_test.clj` |
| HTTP adapter contract | Medium | HTTP goes through command/query ports, task routes are mounted, old routes are gone, OpenAPI request/response schemas hold | `bb test:http-contract`, `http_test.clj` |
| Component lifecycle | Medium | Jetty, datasource and handler lifecycle wiring starts and stops cleanly | `bb test:component-lifecycle`, `system_test.clj` |
| HTTP performance smoke | Medium | Task/query HTTP paths stay inside coarse latency budgets without Docker | `bb test:http-perf`, `http_perf_test.clj` |
| System smoke: HTTP + SQLite | Medium | Jetty, command/query use cases and a file-backed SQLite database work across service restart | `bb test:system-sqlite`, `test/cart/system/http_sqlite_test.clj` |
| System smoke: HTTP + Postgres | Large | Jetty, command/query use cases, runtime DB role and migrated Postgres work together | `bb test:system-postgres`, `test/cart/system/http_postgres_test.clj` |
| Manual local system | Large | Compose Postgres, one-shot Flyway and the API work together for exploration | `bb up`, `bb down` |

Unicode and internationalization boundary coverage is split across these
layers: the serialization tests prove JSON round trips, the HTTP tests prove
UTF-8 request/response handling, the Postgres tests prove UTF8 server/client
encoding plus `text`/`jsonb` persistence, the SQLite tests prove UTF-8 encoding
plus JSON text persistence, and the full-stack smokes prove those paths through
Jetty. These tests use English, Chinese and Arabic text as user data.

## Gates

### Inner Loop

Use the smallest task that covers the code being changed:

| Change | Run |
|---|---|
| Domain rules or aggregate invariants | `bb test:business-logic` |
| Event storage JSON shape | `bb test:serialisation` |
| Command/query/integration-event port behavior | `bb test:unit` |
| In-memory event store | `bb test:outbound-memory` |
| SQLite event store | `bb test:outbound-sqlite` |
| SQLite runtime wiring | `bb test:system-sqlite` |
| HTTP adapter or OpenAPI contract | `bb test:http-contract` |
| Component lifecycle wiring | `bb test:component-lifecycle` |
| HTTP response-time regression | `bb test:http-perf` |
| Migration SQL, PL/pgSQL or Postgres adapter | `bb test:outbound-postgres` |
| Full-stack HTTP + Postgres | `bb test:system-postgres` |

### Before Commit

Run:

```bash
bb precommit
```

`bb precommit` is the no-Docker local gate. It runs:

- `bb check`
- `bb test:business-logic`
- `bb test:serialisation`
- `bb test:unit`
- `bb test:outbound-memory`
- `bb test:outbound-sqlite`
- `bb test:http-contract`
- `bb test:component-lifecycle`
- `bb test:system-sqlite`

It deliberately does not run `bb test:http-perf`. Performance smoke tests are useful
as CI regression guards, but they should not slow every commit attempt.

If a commit changes any of these paths, also run the Docker-backed Postgres
suite before committing:

- `resources/db/postgres/migration/**`
- `resources/docker/postgres/**`
- `compose.yaml`
- `src/cart/adapter/driven/event_store_postgres.clj`
- `test/cart/adapter/driven/append_fn_test.clj`
- `test/cart/adapter/driven/event_store_postgres_test.clj`
- `test/cart/system/http_postgres_test.clj`
- `test/cart/test_db.clj`

Command:

```bash
bb test:postgres
```

`bb test:postgres` is a convenience alias for `bb test:outbound-postgres` plus
`bb test:system-postgres`.

If a commit changes any of these paths, run the SQLite suite before committing
even if you are not running the full precommit gate:

- `resources/db/sqlite/migration/**`
- `src/cart/adapter/driven/event_store_sqlite.clj`
- `test/cart/adapter/driven/event_store_sqlite_test.clj`
- `test/cart/system/http_sqlite_test.clj`

Command:

```bash
bb test:sqlite
```

`bb test:sqlite` is a convenience alias for `bb test:outbound-sqlite` plus
`bb test:system-sqlite`.

### Pull Request / CI Gate

CI must run:

```bash
bb ci
```

`bb ci` runs `bb check` and the full `bb test` suite, including SQLite, HTTP
performance smoke tests, and Postgres 18.4 plus Flyway 13.0.0 through
Testcontainers.

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

For a SQLite release smoke, run `bb run:sqlite`, hit `/health`, `/openapi.json`,
one command and one query, then stop the foreground process and run `bb down` to
remove the local `target/` database.

## Contract-First API Testing

The OpenAPI document at `resources/openapi/cart-api.openapi.json` is the source
of truth.

The HTTP test suite must keep proving:

- `/openapi.json` serves the checked-in file verbatim
- the checked-in document parses with Swagger Parser and has no parser messages
- all command/query routes in the OpenAPI document are mounted
- command and query operations are POST-only
- legacy resource-shaped routes remain absent
- every request-body content type in the contract has a valid test example
- every response status/content-type combination in the contract has a live
  handler response example
- valid requests and live responses are validated with Atlassian's OpenAPI
  request validator
- deliberately invalid request examples fail OpenAPI request validation
- English, Chinese and Arabic JSON text is decoded and encoded as UTF-8

The validator is intentionally test-scoped. It is an HTTP adapter contract test,
not a runtime dependency and not a unit test. The exhaustive example maps are
part of the contract governance: adding a request body, response status or media
type to the OpenAPI file requires adding a live validating example.

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
- it excludes Jetty, TLS, network hops, kernel scheduling and persistent-store
  latency

This makes it a useful regression guard for accidental HTTP adapter overhead,
not a load test. Default budgets are intentionally coarse:

- `POST /queries/get-cart` p95 under `10ms`
- `POST /commands/add-product-item` p95 under `15ms`
- both p99 values under `75ms`

Override those budgets on slower or stricter runners with:

```bash
HTTP_PERF_QUERY_P95_MS=20 HTTP_PERF_COMMAND_P95_MS=30 HTTP_PERF_P99_MS=100 bb test:http-perf
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

The SQLite adapter has no server-side function layer. Its tests prove the same
semantics through the EventStore port, with a file-backed database, WAL mode,
`BEGIN IMMEDIATE`, and multiple pooled connections. These tests must keep
asserting that stale expected-version writes return `[:conflict ...]`, `:any`
never conflicts, multi-event appends stay contiguous, and loser rows are not
written.

Migrations are tested by starting Postgres, then running Flyway in a separate
one-shot container before any Postgres tests run. That mirrors local Compose
and production deployment more closely than in-process migration calls.

The full-stack HTTP + Postgres smoke test is deliberately thin. It does not
duplicate the adapter contract or OpenAPI contract tests; it proves that a real
Jetty server can write through the command HTTP path as `cart_app`, persist into
migrated Postgres, stop, restart, and read the same cart through the query HTTP
path.

The full-stack HTTP + SQLite smoke is similarly thin. It proves that a real
Jetty server can write through the same HTTP command path, stop, restart against
the same file-backed SQLite database, and read through the query path.

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
- Add load tests with real Jetty and persistent stores once target latency/SLO
  numbers exist. The current performance smoke test catches local adapter
  regressions; it does not prove production capacity.
- Make the race iteration count configurable so nightly builds can run a larger
  stress profile than pull requests.
