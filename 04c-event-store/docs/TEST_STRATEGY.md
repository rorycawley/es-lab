# Test Strategy

This strategy adapts the useful testing conventions from `04b-event-store` to
the finalized Use-Case 3.0 requirements and the architecture in this project.
Tests are organized by the claim they prove, not by a target percentage or by
the private function that happens to implement a behavior.

The governing rule is:

> Use the narrowest stable public boundary that can prove the claim, then keep
> a small number of broader tests to prove composition and real infrastructure.

## Terminology and SUT Policy

| Test family | System under test | Purpose |
|---|---|---|
| Domain/programmer test | Public pure domain functions | Prove business decisions, folding and invariants without IO. |
| Application-policy test | A shared application contract | Prove cross-slice policy such as `SWR-022` without transport or persistence. |
| Slice behavior test | One inbound command or query port | Prove actor-task behavior using replaceable outbound test adapters. |
| Outbound port contract test | One outbound port | Prove every memory, SQLite and PostgreSQL implementation is substitutable. |
| HTTP adapter contract test | Ring handler plus OpenAPI | Prove routing, JSON mapping, dispatch and external contract conformance. |
| Component/system smoke | Real Component graph and localhost HTTP | Prove lifecycle and wiring without duplicating lower-level behavior suites. |
| Migration integration test | Flyway plus a real database | Prove an empty database can reach the expected schema repeatably. |
| Acceptance test | Public backend boundary plus a persistent adapter | Prove one identified `SPEC2.md` acceptance case has been delivered. |

Tests should use public namespace functions, protocols, HTTP operations or
database-adapter contracts. They should not call private helpers merely because
those helpers are easy to reach. Private implementation can change without a
test rewrite when observable behavior is unchanged.

The unit of isolation is the test. Tests do not depend on execution order or
state left by another test. A broad test is not stronger merely because it
starts more infrastructure; each broad test must own a composition claim that
cannot be proven more narrowly.

## Test Sizes

| Size | Allowed resources | Examples |
|---|---|---|
| Small | CPU and memory only; no network or database | Outcome pipeline behavior. |
| Medium | Local files, threads, embedded SQLite or localhost network | OpenAPI parsing, Ring/Jetty, SQLite migrations, architecture and traceability inspection. |
| Large | Docker or an external process/database | PostgreSQL plus one-shot Flyway through Testcontainers. |

Size describes isolation, not duration. A warm Docker test may run faster than
a cold Clojure process and remains a large test.

## Iteration 0 Evidence

Iteration 0 establishes decisions and a walking skeleton. It deliberately
delivers no cart use-case slice, so none of the 91 `SPEC2.md` acceptance cases
is marked Verified yet. `docs/requirements-traceability.md` proves that every
case is uniquely scheduled and has an `SWR-008` outcome; it is not a substitute
for executing those cases once their slices exist.

| Claim | Size | Suite | Evidence |
|---|---:|---|---|
| `SWR-022` has one fixed short-circuit order | Small | `bb test:policy` | Application-policy tests cover all four terminal outcomes, first addition and overlap precedence. |
| Local configuration enforces the trust-boundary constraint | Small | `bb test:config` | Loopback defaults, wildcard refusal and explicit trusted-upstream opt-in. |
| Source namespaces obey dependency direction | Medium | `bb test:architecture` | Path/namespace correspondence and forbidden domain/application dependencies. |
| The checked-in API is a valid task-based OpenAPI contract | Medium | `bb test:http-contract` | Swagger Parser, request validator, closed object schemas and stable statuses/codes. |
| Operational HTTP is correctly wired | Medium | `bb test:component` | Ring tests plus a real Jetty server on an ephemeral localhost port. |
| Every acceptance case is scheduled once | Medium | `bb test:traceability` | Exact set equality, no duplicate IDs, legal outcomes and iteration allocation. |
| SQLite migration is separate and repeatable | Medium | `bb test:migrations` | Flyway runs twice against a new file and owns schema history. |
| PostgreSQL migration is separate and repeatable | Large | `bb test:migrations-postgres` | Testcontainers PostgreSQL 18.4 plus two Flyway 13 one-shot runs. |

## Outcome-Pipeline Testing

`cart.application.command-pipeline/evaluate` is shared application policy, not
domain logic and not a complete slice. Its tests must prove observable control
flow through supplied step contracts:

1. Invalid input terminates before replay, currency and business rules.
2. Accepted replay or request-ID misuse terminates before currency and business
   rules.
3. Stale observation terminates before business rules.
4. A current observation reaches business rules.
5. First addition skips only observation currency; validation, replay and
   business rules still run.
6. Each `proceed` value becomes the next step's context.
7. Exceptions from steps propagate; the pipeline does not misreport operational
   failures as business outcomes.

Overlap tests must name the precedence they protect: unauthentic plus stale is
invalid, replay plus stale is success, replay plus closed is success, and stale
plus closed is conflict.

## Contract-First HTTP Testing

`resources/openapi/cart-api.openapi.json` is the external contract. During
Iteration 0 the HTTP contract suite must prove:

- `/openapi.json` serves the checked-in document verbatim
- Swagger Parser resolves the document without messages
- every declared business operation is POST-only
- every request-body media type has a conforming example
- deliberately invalid UUID, quantity, unknown-field and partial first-add
  examples fail request validation
- every component object schema is closed with `additionalProperties: false`
- all four command outcome statuses and stable rejection codes are declared
- live health, readiness and OpenAPI responses conform to their schemas
- business paths remain unmounted until their slices are delivered

Once a business route is mounted, its HTTP adapter contract injects inbound-port
stubs that produce each declared application outcome. This proves every response
mapping conforms and that no undeclared status can escape without requiring a
not-yet-delivered domain scenario. Acceptance tests use the real handler and
exercise only behavior delivered by the current iteration. Contract validation
remains test-scoped rather than becoming runtime request middleware.

## Migration Testing

Service startup never runs migrations. SQLite uses embedded Flyway through the
separate migration entry point. PostgreSQL tests use the same topology as local
operation: a PostgreSQL container with initialization scripts and a separate
one-shot Flyway container mounting the checked-in migration directory.

Migration tests start from a new database, run twice to prove idempotence, and
assert schema-history ownership. Docker-backed tests own and stop their network
and containers even when an assertion fails. Testcontainers reuse is disabled.

The empty Iteration 0 migration roots intentionally apply zero versioned
migrations. Iteration 1 replaces this baseline with schema version 1 for streams,
events, command requests and both projections. Migration tests must then require
that exact version and the required tables; silently remaining at zero is a test
failure.

## Iteration 1 Evidence

Iteration 1 delivers 14 acceptance cases: all of `UC-01/S01`, all of
`UC-01/S02`, and `UC-02/S01/TC01`. The suites are divided by the claim they own:

| Claim | Task | Adapter matrix | Gate |
|---|---|---|---|
| Pure fold, evolve, add decisions, projectors and observation codec | `bb test:core` | None | Precommit and CI |
| Add/view behavior through inbound ports | `bb test:slices` | Memory test adapters | Precommit and CI |
| Outbound event, projection, idempotency and unit-of-work contracts | `bb test:adapter` / `bb test:postgres` | Memory and SQLite / Testcontainers PostgreSQL | Precommit / CI |
| Public acceptance through the Ring boundary | `bb test:acceptance` / `bb test:postgres` | SQLite / Testcontainers PostgreSQL | Precommit / CI |
| Real server composition | `bb test:component` | Memory | Precommit and CI |
| Version 1 migrations | `bb test:migrations` / `bb test:migrations-postgres` | SQLite / PostgreSQL | Precommit / CI |

Each acceptance test retains its exact `SPEC2.md` identifier in executable test
data or its test name. The same 14-case suite runs through the Ring handler.
Each SQLite case receives a newly migrated database. PostgreSQL cases use a
suite-scoped `PostgreSQLContainer`, migrated by a one-shot Flyway
`GenericContainer` on the same private Testcontainers network, with all
application tables reset between cases. Scenario setup uses public commands
rather than direct row insertion. Adapter inspection is allowed after the public
interaction only for otherwise invisible durability claims such as no extra
event, projection or idempotency row. Memory proves fast slice behavior and
participates in the shared outbound contract, but it is not treated as
persistent acceptance evidence.

The equal-delivery races in `UC-01/S01/TC06` and `UC-01/S02/TC04`, and the
non-equal global request-ID race in `UC-01/S01/TC08`, also run through the inbound
port against every adapter. Persistent races use separate connections, a barrier
before the contested operation and bounded completion waits. Tests assert both
responses and durable rows: one accepted command, the expected event count,
aligned projections and no partial loser state.

The Iteration 1 traceability rows are Verified because their SQLite and
PostgreSQL public-boundary cases both pass. The real Jetty component suite
contains one first-add-then-view smoke test; repeating the 14-case matrix over a
socket would add runtime without proving another composition claim.

## Gates

Use the smallest relevant task while editing:

| Change | Command |
|---|---|
| Outcome policy | `bb test:policy` |
| Domain, projectors or observation codec | `bb test:core` |
| Add/view application behavior | `bb test:slices` |
| Memory or SQLite persistence behavior | `bb test:adapter` |
| SQLite public acceptance | `bb test:acceptance` |
| PostgreSQL adapter or acceptance behavior | `bb test:postgres` |
| Runtime configuration | `bb test:config` |
| HTTP router or OpenAPI | `bb test:http-contract` |
| Component lifecycle | `bb test:component` |
| Namespace/module boundaries | `bb test:architecture` |
| Traceability document or `SPEC2.md` cases | `bb test:traceability` |
| SQLite migration configuration | `bb test:migrations` |
| PostgreSQL init or migration configuration | `bb test:migrations-postgres` |

Before commit, run the no-Docker gate:

```sh
bb precommit
```

The pull-request/CI gate is:

```sh
bb ci
```

`bb ci` runs static checks, every no-Docker suite and the Testcontainers
PostgreSQL migration suite. It must start from disposable infrastructure rather
than relying on a developer's Compose database.

## Isolation and Failure Diagnostics

- Temporary SQLite paths are unique per test and live under the OS temporary
  directory or ignored `target/` output.
- Real HTTP tests create a new `HttpClient` scoped no longer than the server.
- Testcontainers own explicit lifecycle and never use reusable containers.
- Concurrent tests use barriers and bounded waits once concurrency behavior is
  introduced.
- Assertions include the contract parser messages, validator report or database
  state needed to locate a failure.
- Random test order is retained; an order-dependent pass is a defect.
- Flaky tests are fixed at their isolation or lifecycle boundary, not hidden by
  unconditional retries.

## Evolution by Iteration

Iteration 1 adds pure domain and observation-codec tests, inbound add/view port
tests, shared persistence port contracts and the first 14 acceptance tests. Each
later slice adds its acceptance IDs to executable test names while retaining
focused lower-level evidence. A row in requirements traceability moves from
Planned to Verified only when its public-boundary acceptance test passes against
the required persistent adapters.
