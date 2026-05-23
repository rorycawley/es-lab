# Spec: 03-task-persistence

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
- Given the application is loading the list, then I see a loading indicator

**Verified by:** `bb test:frontend` — Jest unit tests cover the loading, empty, and populated list states

---

## Story 4 — Data durability

> As a developer, I want submitted requests to be stored in Postgres and retrievable after the fact, so that the persistence layer is proven to work end-to-end.

**Acceptance criteria:**
- Given the stack is running, when I submit a request via the command endpoint, then the response includes a `request_id`
- Given the stack is running, when I submit a request and then call the list query endpoint, then the submitted request appears in the response
- Given the stack is running, when I call the list query endpoint, then the response body has a `requests` key containing an array

**Verified by:** `bb test:acceptance` — Babashka HTTP tests run a submit-then-list roundtrip against the live stack

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
- Given the stack is running, when I stop it, then all processes terminate and no containers remain running

**Verified by:** `bb test:acceptance` and `bb test:e2e` — both call `docker compose down` in a finally block after every run
