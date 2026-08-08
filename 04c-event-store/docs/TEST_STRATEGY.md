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

Once a business route is mounted, the same suite must add live conforming
examples for every declared response status and must walk handler to contract so
no undeclared status can escape. Contract validation remains test-scoped rather
than becoming runtime request middleware.

## Migration Testing

Service startup never runs migrations. SQLite uses embedded Flyway through the
separate migration entry point. PostgreSQL tests use the same topology as local
operation: a PostgreSQL container with initialization scripts and a separate
one-shot Flyway container mounting the checked-in migration directory.

Migration tests start from a new database, run twice to prove idempotence, and
assert schema-history ownership. Docker-backed tests own and stop their network
and containers even when an assertion fails. Testcontainers reuse is disabled.

The empty Iteration 0 migration roots intentionally apply zero versioned
migrations. A later iteration replacing this baseline must change the assertions
to require the first schema version; silently remaining at zero is then a test
failure.

## Gates

Use the smallest relevant task while editing:

| Change | Command |
|---|---|
| Outcome policy | `bb test:policy` |
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

Iteration 1 adds pure domain tests, inbound add/view port tests, observation
codec tests, shared persistence port contracts and the first nine acceptance
tests. Each later slice adds its acceptance IDs to executable test names while
retaining focused lower-level evidence. A row in requirements traceability moves
from Planned to Verified only when its public-boundary acceptance test passes
against the required persistent adapters.
