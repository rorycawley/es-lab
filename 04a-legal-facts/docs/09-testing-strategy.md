# Testing Strategy

*Status: Accepted.*
*Date: 2026-05-26.*


## Testing Philosophy

This document follows Kent Beck's *Test-Driven Development: By Example* as its
primary reference. The principles below are not preferences — they govern every
testing decision in this project.


### The reason to test is a new behaviour, not a method on a class

A test exists because there is something the software must do. The trigger is
always a requirement — a user story, an acceptance criterion, a business rule.
A test that exists because a class or method was written is testing the wrong
thing. Tests protect requirements. Tests do not protect implementation details.


### The test pyramid

```
        ▲
       /UI\          — few; slow; prove the system works end-to-end
      /────\
     / Integ\        — some; verify ports connect correctly to adapters
    /─────────\
   / Unit Tests \    — many; fast; test behaviour at the module boundary
  /─────────────\
```

*After Mike Cohn, Martin Fowler et al.*

Most tests live at the bottom. A wide base of fast, behavioural tests at the
module boundary. A smaller set of integration tests confirming adapters wire
correctly. A thin layer of system tests for end-to-end confidence. UI tests and
Gherkin-style executable specifications (Fitnesse, SpecFlow, Cucumber, ATDD)
are not used — they are slow, brittle, and test through the wrong layer.


### Scaffold tests and keeper tests

When building a feature, developer tests are used as scaffolding — constraints
and guardrails for small iterations. These are temporary. Once the feature is
complete, scaffold tests are deleted.

A building uses an enormous amount of scaffolding during construction. Once
the building stands, the scaffolding is torn down. Keeping it would make the
building impossible to modify. Scaffold tests kept after development is done
become exactly this: they couple tests to implementation details, require
changes whenever the internals are rearranged, and turn refactoring into a
test-fixing exercise.

**The rule:** delete scaffold tests when development of that piece is done.
The tests that survive are the ones that protect a requirement.

**Write dirty code to get green, then refactor.** No new tests are written
during the Refactor phase. No new tests for refactored internals, private
functions, or intermediate classes. The surviving test suite covers the
public boundary. The internals are free to change.


### Test the port, not the implementation

"Unit" means module — not a class, not a method. The unit of test is the
publicly exported surface of a module: its port, its API, its stable contract.
The unit of isolation is the test itself.

Both development (TDD) and acceptance (demonstrating a story is done) use
tests written against this port. The same test boundary serves both purposes.

This derives from information hiding: implementation details are hidden behind
a public facade kept deliberately stable. Tests against the facade do not
break when the internals change. When you can refactor freely without touching
tests, the boundary is drawn correctly.

> "We must not couple tests to implementation. Test only the public API."

A test whose intent is not immediately clear when you read it is testing the
wrong thing. If a test only exercises mock behaviour rather than real
behaviour, it is testing the mock, not the software.


### What not to mock

Do not mock internals, private functions, or adapters. Adapters are tested
separately with real infrastructure (Layer 2 below). Domain logic is tested
against the Decider directly — no mocks needed because the Decider is a pure
function. The only test doubles used are for external systems the team does not
control, and those doubles implement the full port interface, not a partial mock.

Avoid IoC container wiring in tests. Tests construct their own dependencies
directly. A test that relies on a container to assemble its subject under test
is not isolated.


### TDD helps design the best possible API

An advantage of writing the test first is that it forces you to design the
public interface before you write the implementation. If the test is hard to
write, the API is wrong. The test suite is the first consumer of the module.
If it is unpleasant to use, the module will be unpleasant to use.


## Goals

- Every behaviour described in a user story acceptance criterion has a
  corresponding automated test with the same ID, written against the module's
  public port — not against its internals.
- Every architectural characteristic defined in
  `06-architectural-characteristics.md` is verified by a fitness function that
  runs in CI.
- The feedback loop for a developer making a change is under 10 seconds
  locally on the happy path.
- External systems the team does not control (payment gateway, identity broker,
  address validation) are never a test dependency for the domain.
- Refactoring never breaks the test suite. If it does, the tests are coupled
  to implementation details and must be fixed to target the public boundary.


## Test Layers

Tests are organised into five layers. Each layer has a defined scope, speed
target, and placement in the commit/push/CI workflow.

### Layer 1 - Pure Function Tests (tests written on a port)

**Scope:** Deciders, business rule predicates, FSM transition validation,
event evolution functions, domain utilities.

**The port:** The Decider `decide` function is the stable public contract of
the domain module. Tests call it directly with state and command maps and
assert on the returned events or errors. No mocks. No infrastructure. The
Decider is a pure function: same input, same output, always.

These are the primary keeper tests. They survive because they protect
requirements (user stories and acceptance criteria), not because they were
scaffolded during implementation. Scaffold tests — tests written to work
out internal logic during a development session — are deleted before the
work is committed.

**Characteristics:** No I/O. No database. No containers. No network. Pure
functions in, assertions out.

**Speed target:** Full suite under 2 seconds.

**Runs:** Pre-commit hook (mandatory gate before every local commit).

**Naming:** Test names reference acceptance criterion IDs:
```clojure
(deftest AC-AP-007-005-submit-draft-rejects-missing-company-name ...)
```

**What this catches:** Logic errors in business rules, invalid FSM transitions,
broken invariants in the domain model.


### Layer 2 - Adapter / Integration Tests (ports to adapters)

**Scope:** Database adapters (event store, command ledger, projections, audit
log), outbox publisher. Each test exercises one adapter against a real instance
of its dependency.

**The port:** Each adapter test calls the port interface and asserts that the
adapter implements it correctly against real infrastructure. Adapters are not
mocked — they are tested here, with real Postgres, so they can be trusted
everywhere else. Domain logic is not in scope.

**Characteristics:** Uses Testcontainers to spin up a real Postgres instance.
No domain business logic. No mocking of adapters.

**Speed target:** Full suite under 45 seconds.

**Runs:** Pre-push hook.

**Infrastructure:** Testcontainers pulls containers locally. In CI, the same
containers run in the pipeline. See `docs/setup/testcontainers.md` for
Rancher Desktop configuration.

**What this catches:** Schema mismatches, SQL bugs, connection handling,
transaction boundaries, adapter interface compliance.


### Layer 3 - Behavioural / Acceptance Tests (system port)

**Scope:** Full vertical slices — HTTP request in, events persisted,
projection updated, HTTP query response out. These verify acceptance criteria
for user stories. Both development (TDD) and acceptance (story sign-off) use
these tests.

**The port:** The HTTP API is the system-level port. Tests call it directly
with realistic inputs and assert on observable outputs. They do not reach
inside the system to check internal state. External systems the team does not
control are replaced by test doubles that implement the full port interface —
not partial mocks (see Mocking Strategy below).

**Characteristics:** The application runs with Testcontainers infrastructure.
Tests follow Given/When/Then matching the acceptance criteria. They are not
written in Gherkin or any executable specification format — they are standard
developer tests in Clojure, readable by anyone who can read code.

**Speed target:** Full suite under 3 minutes.

**Runs:** CI on every push to trunk.

**Naming:** Test files are named after the user story they exercise
(`us_ap_007_submit_draft_test.clj`). Each test case is named after its
acceptance criterion ID (`AC-AP-007-001`).

**What this catches:** Integration between domain, adapters, and API; full
workflow correctness; role-based access enforcement; idempotency of the full
stack.


### Layer 4 - Fitness Function Tests

**Scope:** Architectural characteristics that cannot be verified by a single
acceptance criterion. These are defined in `06-architectural-characteristics.md`
with IDs `FF-001` through `FF-010`.

**Characteristics:** May require a running system or a real database. Some
fitness functions are structural (database permission checks, code-structure
assertions); others are behavioural (rebuild a read model from events, replay
a duplicate command).

**Speed target:** Under 5 minutes.

**Runs:** CI on every push to trunk, as a separate suite from behavioural
tests so failures are clearly attributed.

**Examples:**
- `FF-001` - query company existence from events; prove approval row alone is
  insufficient.
- `FF-002` - drop projection, replay events, compare results.
- `FF-004` - submit same command twice; assert no duplicate side effects.
- `FF-006` - attempt approval from every non-`ReadyForDecision` state; assert
  rejection.


### Layer 5 - Contract Tests

**Scope:** API consumer contracts. The API is versioned (ADR-0021); each
version must satisfy the contracts of its consumers. Consumer-driven contract
tests (Pact or equivalent) verify that provider responses match what consumers
expect.

**Speed target:** Under 2 minutes.

**Runs:** CI. Contract verification runs against the real API; contract
publishing runs from consumer codebases.

**What this catches:** Breaking changes to API responses that would silently
break a frontend or integration client.


### Out of CI - Performance and Load Tests

Performance and load tests are maintained in the `perf/` directory and run
separately from the CI gate. They are not blocking. They exist to establish
baselines and detect regressions in throughput or latency before a release.


## Testcontainers

Testcontainers is a library that programmatically starts and stops real Docker
containers — Postgres, and any other infrastructure dependency — directly from
test code, with no separate setup step and no persistent local service required.
Understanding why it is used is inseparable from understanding the test strategy.


### Why Testcontainers makes "don't mock adapters" viable

The philosophy above says: do not mock adapters — test them with real
infrastructure. Testcontainers is what makes this economically feasible. Without
it, testing an adapter against real Postgres would require every developer to
run a local database, manage its schema, and keep it consistent with CI. With
Testcontainers, the test itself starts the container, runs migrations, executes
the test, and tears the container down. The test is self-contained and portable.

This is the reason Layer 1 (pure function) tests can assert confidently that
the Decider works correctly without any infrastructure at all — the adapters
have been separately verified in Layer 2 against a real database, using
Testcontainers. The two layers trust each other because each does one job
completely.


### Where Testcontainers is used

| Layer | Infrastructure started | Purpose |
|-------|----------------------|---------|
| Layer 2 — Adapter tests | Postgres | Verify each adapter implements its port correctly against real SQL, real transactions, real constraints |
| Layer 3 — Behavioural tests | Postgres | Run the full application stack; events are persisted, projections updated, queries return real data |

Layer 1 (pure function) tests start no containers. The Decider is a pure
function. Layer 4 and Layer 5 may use a running system rather than
Testcontainers depending on the fitness function.


### Test isolation

Each test creates its own state and tears it down. No test assumes any
particular database state left by a previous test. This is what the principle
"the unit of isolation is the test" means in practice.

Two mechanisms are used:

**Transaction rollback** (preferred for Layer 2): the test wraps its database
operations in a transaction and rolls back at the end. No data persists between
tests. This is fast — no container restart between tests.

**Schema reset** (used for Layer 3 full-stack tests): the application starts
against a fresh schema. Tests run against a known-empty database. Slower but
necessary when the full application stack manages its own connections.

Because tests are isolated, they can run fully in parallel and finish in
seconds. Shared state between tests is the primary cause of flaky test suites.
Testcontainers with isolation eliminates that cause.


### Local and CI parity

The same containers run locally and in CI. There is no "works on my machine"
class of failure. A test that passes locally passes in CI because both
environments start from the same container image.

Local setup requires Rancher Desktop (or Docker Desktop) with the
`api.version=1.44` configuration. See `docs/setup/testcontainers.md`.


## Development Workflow and Git Gates

### The Rule: Integrate Before You Commit

Trunk-based development requires that you always work on top of the latest
state of trunk. Before committing:

1. `git pull --rebase origin main` - integrate any changes from trunk.
2. Resolve conflicts if any arise.
3. Run the pre-commit hook (Layer 1 tests).
4. Commit.

This is enforced by convention and reinforced by the pre-push hook, which
runs the full fast suite (Layers 1 + 2) and rejects a push if tests fail.

### Pre-Commit Hook (Layer 1 only)

Runs automatically on `git commit`. Executes pure function tests only. If this
fails, the commit is aborted. Fix the test, then commit.

```bash
# .git/hooks/pre-commit (or managed via lefthook/husky)
bb test:pure
```

Target: completes in under 3 seconds. If it grows past 5 seconds, move slow
tests to a higher layer.

### Pre-Push Hook (all layers)

**Status: active.** The hook lives at `.git/hooks/pre-push` in the monorepo
root and is already enforced on every push.

Behaviour:
- Inspects each ref being pushed. If the changeset touches any file under
  `04a-legal-facts/`, it runs `bb test:backend` from that directory.
- Pushes that touch only other projects pass through without running these tests.
- If the remote branch has commits the local branch does not have, the push is
  aborted with instructions to integrate first (`git pull --rebase origin main`).
  This enforces the trunk-based rule at the gate.
- If tests fail, the push is rejected.

```bash
# .git/hooks/pre-push (monorepo root — already in place)
# Runs bb test:backend for 04a-legal-facts when that project is touched.
```

### CI Pipeline (All Layers)

Every push to trunk triggers:

1. **Build** - compile, lint, check.
2. **Layer 1** - pure function tests.
3. **Layer 2** - adapter/integration tests (Testcontainers).
4. **Layer 3** - behavioural/acceptance tests.
5. **Layer 4** - fitness functions.
6. **Layer 5** - contract tests.

All layers must pass for a green build. Layers run sequentially to fail fast
on the cheapest tests first.


## Mocking Strategy for External Systems

The domain is separated from external systems by ports (ADR-0019). In tests,
adaptors for external systems are replaced by test doubles. These are not
mocks of internal functions - they are real implementations of the port
interface that behave deterministically in a test context.

| External System | Test Double Approach |
|-----------------|---------------------|
| Payment gateway (Stripe) | In-process stub implementing the payment port. Returns success/failure based on test data. For full-stack contract tests, WireMock records and replays Stripe API responses. |
| OIDC identity broker | Test JWT factory that mints tokens with configurable claims. A test OIDC discovery endpoint is served in-process. |
| Address validation service | In-process stub implementing the address validation port. Default: valid. Test cases that exercise BR-AP-009 configure it to return unavailable or invalid. |
| External verified-person registry | In-process stub implementing the person-verification port. Returns pre-loaded test identities. |
| Email / notification delivery | In-process stub that records sent notifications. Tests assert what was sent, not that a real email was delivered. |

**Rule:** Test doubles implement the port interface completely. They are not
partial mocks. This ensures that a slow integration test with a real adapter
can always be substituted for a fast test with a stub without changing the
test setup.

**Do not mock internals, private functions, or adapters.** Mocking an adapter
does not test the adapter — it tests the mock. Adapter correctness is verified
in Layer 2 with real infrastructure. Domain correctness is verified in Layer 1
with pure functions. There is no remaining case that requires mocking an
internal. If the test setup feels like it requires one, the boundary is wrong.


## Behavioural Test Structure

Behavioural tests follow the Given/When/Then structure of the acceptance
criteria they exercise. The structure is readable to a non-technical reviewer.

```clojure
(deftest AC-AP-007-001-submit-active-draft-with-valid-data-and-payment
  (testing "Given an active draft with valid data and payment succeeds,
            when submitted, draft transitions to Submitted and application is created"
    (let [draft-id   (create-draft! applicant-session valid-draft-data)
          result     (submit-draft! applicant-session draft-id payment-ok)]
      (is (= :submitted (draft-status draft-id)))
      (is (some? (:application-id result))))))
```

Each test:
- Uses the acceptance criterion ID as the test name prefix.
- Uses real application behaviour through the API or service boundary (not
  unit-testing internals).
- Sets up state via commands, never by writing directly to the database.
- Asserts observable outcomes (state, events, responses), not internal state.


## Fitness Function Test Structure

Fitness functions are named by their ID and describe the architectural
property they protect.

```clojure
(deftest FF-001-no-company-exists-without-registered-company-created
  (testing "Approval event alone does not make a company exist"
    (let [app-id (create-approved-application-without-company-created!)]
      (is (nil? (find-company-by-application-id app-id))))))
```


## Test Data Strategy

- **Stable fixtures** are defined as named test actors (`verified-director-1`,
  `applicant-session`, `examiner-session`) in shared test helpers.
- **Realistic IDs** use UUIDs generated per test run, never hardcoded strings.
- **Configurable values** (fee amounts, expiry periods, prohibited categories)
  use test-specific configuration, never production values.
- **No shared mutable state** between tests. Each test starts from a clean
  slate (isolated database schema or transaction rollback).
