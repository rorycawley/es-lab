# Spec: 03-task-persistence

## How to read this document

**User stories** capture intent: who wants something, what they want, and why. They are written from the outside — from the perspective of a user or developer interacting with the system — and deliberately say nothing about implementation. A story answers the question "what problem are we solving and for whom?"

**Acceptance criteria** define done. Each criterion is a testable condition: given some starting state, when something happens, then a specific observable outcome must be true. They set the boundary of the story — anything not described here is out of scope for the story. Acceptance criteria are precise enough to verify but do not prescribe how to implement. The same criterion can be satisfied by many different technical approaches.

**Implementation** is how the code satisfies the acceptance criteria. Implementation decisions — which libraries, data structures, SQL queries, HTTP status codes, component state machines — live in [ARCHITECTURE.md](ARCHITECTURE.md), not here. A decision belongs in the spec only if it is observable from outside the system and therefore testable; otherwise it belongs in the architecture document.

The consequence: if a behaviour is not described in an acceptance criterion, there is no requirement for it to exist, and changing or removing it is not a regression. Conversely, if a behaviour is described here, it must be verified by an automated test that references the criterion.

## Story 1 — Zero-dependency setup

> As a developer, I want to run the full stack locally without installing any language runtimes or build tools on my machine, so that I can get it running regardless of my existing environment.

**Acceptance criteria:**
- Given a machine with only Docker installed, when I start the stack, then the frontend, backend, and Postgres database all run successfully without any additional installation steps

**Verified by:** `docker compose up --wait` — the stack starts cleanly with only Docker installed. `bb test:acceptance` also exercises this as a side-effect (the stack-fixture runs `docker compose up` before every acceptance test run), but running the automated verifier requires the project toolchain (Babashka, mise).

---

## Story 2 — Submit a service request

> As a user, I want to submit a service request through the browser, so that my issue is recorded and persisted.

**Acceptance criteria:**
- Given the stack is running, when I fill in the Title and Description fields and click Submit, then I see "Request submitted successfully"
- Given I have just seen "Request submitted successfully", when a few seconds pass without further interaction, then the success message disappears
- Given the stack is running, when I submit a request, then the request appears in the Submitted Requests list immediately after
- Given the stack is running, when I submit a request without a title, then submission does not proceed
- Given the stack is running, when I submit a request without a description, then submission does not proceed

**Verified by:** `bb test:frontend` — Jest unit tests with mocked HTTP cover all UI states; `bb test:e2e` — Playwright submits a real request in a browser and asserts it appears in the list

---

## Story 3 — View submitted requests

> As a user, I want to see all submitted requests when I open the application, so that I know what is already in the system.

**Acceptance criteria:**
- Given the stack is running and no requests have been submitted, when I open the application, then I see "No requests yet."
- Given the stack is running and requests have been submitted, when I open the application, then I see the list of requests with their titles, descriptions, and statuses
- Given multiple requests have been submitted, when I view the list, then the most recently submitted request appears first
- Given the application is loading the list, then I see a loading indicator

**Verified by:** `bb test:frontend` — Jest unit tests cover the loading, empty, and populated list states

---

## Story 4 — Data durability

> As a developer, I want submitted requests to be stored in Postgres and retrievable after the fact, so that the persistence layer is proven to work end-to-end.

**Acceptance criteria:**
- Given the stack is running, when I submit a request via the command endpoint, then the response status is 201 and the body includes a `request_id`
- Given the stack is running, when I submit a request and then call the list query endpoint, then the submitted request appears in the response
- Given a request has been submitted, when Postgres and the backend are restarted, then the submitted request is still returned by the list query endpoint
- Given the stack is running, when I call the list query endpoint, then the response body has a `requests` key containing an array
- Given a fresh database, when backend migrations run, then every expected migration is recorded in the migration metadata table
- Given a fresh database, when an audit event references a missing service request, then the database rejects the audit event and no orphan audit row is stored

**Verified by:** `bb test:acceptance` — Babashka HTTP tests run submit-then-list and restart durability checks against the live stack; `bb test:backend` — Postgres adapter tests assert the expected Flyway metadata rows exist after migration and that audit events cannot reference missing service requests

---

## Story 5 — E2E browser roundtrip

> As a developer, I want a browser-level test that confirms a submitted request appears in the list in a real browser, so that I know the full stack — Postgres, backend, nginx proxy, and Angular — is wired together correctly.

**Acceptance criteria:**
- Given the full Docker stack is running, when Playwright fills in the form and clicks Submit, then it finds the submitted request title visible in the list

**Verified by:** `bb test:e2e` — Playwright drives a real Chromium browser against the live Docker stack

---

## Story 6 — Clean shutdown

> As a developer, I want to stop the stack cleanly, so that no orphaned processes are left consuming resources.

**Acceptance criteria:**
- Given the stack is running, when I stop it, then all project Compose services terminate and no project containers remain running
- Given the stack is running, when I stop it with `bb down`, then the project Postgres data volume is removed
- Given an automated test run starts or finishes, then the project Postgres test volume is removed so test data does not leak across runs

**Verified by:** `bb test:acceptance` and `bb test:e2e` — both call `docker compose down -v` around the run and assert that no Compose services remain running. `bb down` and `bb reset` both remove the named Postgres data volume when a fully clean manual slate is needed.

---

## Story 7 — Search submitted requests

> As a user, I want to search submitted service requests by title and description, so that I can find relevant historical requests without reading the full list.

**Acceptance criteria:**
- Given the application is open, when I type a search term into the Search requests field, then the list updates to show matching requests
- Given the Search requests field is blank, then the application shows the full submitted requests list
- Given I type a search term and then clear it, then the application returns to the full submitted requests list
- Given no submitted requests match the search term, then the application shows "No matching requests."
- Given submitted requests exist, when I search for a term in a request title, then matching requests are returned
- Given submitted requests exist, when I search for a term in a request description, then matching requests are returned
- Given no submitted requests match the search term, when I search, then the response contains an empty `requests` array
- Given a search term is missing or blank, when I call the search query endpoint, then the backend returns `422`
- Given one request matches in the title and another matches in the description, when I search for the shared term, then the title match is ranked first
- Given I call the search query endpoint, then each result in the response contains only the standard request fields and does not include internal scoring data

**Verified by:** `bb test:frontend` — Jest unit tests cover debounced active search, clearing the search, and search empty state; `bb test:e2e` — Playwright submits a real request, searches for it, and checks the no-match state; `bb test:backend` — handler, HTTP integration, and Postgres adapter tests cover validation, matching, empty results, and rank ordering; `bb test:acceptance` — live-stack HTTP tests cover submit-then-search and blank-query rejection.
