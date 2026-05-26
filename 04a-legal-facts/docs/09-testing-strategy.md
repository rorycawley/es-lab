# Testing Strategy

*Status: Accepted.*
*Date: 2026-05-26.*


## Goals

- Every behaviour described in a user story acceptance criterion has a
  corresponding automated test with the same ID.
- Every architectural characteristic defined in
  `06-architectural-characteristics.md` is verified by a fitness function that
  runs in CI.
- The feedback loop for a developer making a change is under 10 seconds
  locally on the happy path.
- External systems the team does not control (payment gateway, identity broker,
  address validation) are never a test dependency for the domain.


## Test Layers

Tests are organised into five layers. Each layer has a defined scope, speed
target, and placement in the commit/push/CI workflow.

### Layer 1 - Pure Function Tests

**Scope:** Deciders, business rule predicates, FSM transition validation,
event evolution functions, domain utilities.

**Characteristics:** No I/O. No database. No containers. No network. Pure
functions in, assertions out.

**Speed target:** Full suite under 2 seconds.

**Runs:** Pre-commit hook (mandatory gate before every local commit).

**Naming:** Test names reference acceptance criterion IDs where applicable:
```clojure
(deftest AC-AP-007-005-submit-draft-rejects-missing-company-name ...)
```

**What this catches:** Logic errors in business rules, invalid FSM transitions,
broken invariants in the domain model.


### Layer 2 - Adapter / Integration Tests

**Scope:** Database adapters (event store, command ledger, projections, audit
log), message bus adapters, outbox publisher. Each test exercises one adapter
against a real instance of the dependency.

**Characteristics:** Uses Testcontainers to spin up real Postgres and RabbitMQ
(or equivalent) instances. No domain business logic in scope.

**Speed target:** Full suite under 45 seconds.

**Runs:** Pre-push hook.

**Infrastructure:** Testcontainers pulls containers locally. In CI, the same
containers run in the pipeline. See `docs/setup/testcontainers.md` for
Rancher Desktop configuration.

**What this catches:** Schema mismatches, ORM/SQL bugs, connection handling,
transaction boundaries, adapter interface compliance.


### Layer 3 - Behavioural / Acceptance Tests

**Scope:** Full vertical slices through the system - HTTP request in, events
persisted, projection updated, HTTP query response out. These are the tests
that verify acceptance criteria for user stories.

**Characteristics:** The application starts with in-memory or Testcontainers
infrastructure. External systems (Stripe, OIDC broker, address validation) are
replaced by test doubles (see Mocking Strategy below). Tests are written in
Given/When/Then structure matching the acceptance criteria.

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

### Pre-Push Hook (Layers 1 + 2)

Runs automatically on `git push`. Executes pure function tests and adapter
integration tests. If this fails, the push is rejected.

```bash
# .git/hooks/pre-push
bb test:fast
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
