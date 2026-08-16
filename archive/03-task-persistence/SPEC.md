# Spec: 03-task-persistence

## How to read this document

**User stories** capture intent: who wants something, what they want, and why. They say nothing about implementation.

**Acceptance criteria** define done. Each criterion follows the form: given a starting state, when something happens, then a specific observable outcome is true. A criterion is testable when its outcome can be verified by an automated test without knowledge of the implementation.

**Quality attributes** are non-functional requirements that cut across all stories. They generate their own criteria and tests.

Every automated test must reference at least one AC or QA criterion by ID. A test with no such reference should be deleted.

**Definitions used throughout:**
- *service request* — a user-submitted record representing an issue or need (e.g. "printer is broken"). It has a title, description, status, submitter identity, and timestamps. Stored in the `service_requests` table and identified by a `request_id`.
- *blank* — an empty string or a string containing only whitespace characters
- *non-blank* — a string containing at least one non-whitespace character
- *full-text match* — case-insensitive, word-boundary tokenisation as implemented by Postgres `plainto_tsquery`

---

## US-01 — Zero-dependency setup

> As a developer, I want to run the full stack locally with only Docker, so that I can run it regardless of what is installed on my machine.

**AC-01-01** — Given a machine with only Docker installed, when `docker compose up --wait` runs, then the command exits with code 0; postgres, backend, and frontend are healthy; and migrate has completed successfully.

**AC-01-02** — Given the stack is running, when `GET http://localhost:4200/` is called, then the response status is 200, the `Content-Type` header begins with `text/html`, and the response body contains the string `<app-root`.

---

## US-02 — Submit a service request

> As a user, I want to submit a service request through the browser, so that my issue is recorded.

**AC-02-01** — Given the form is idle, when a non-blank Title and a non-blank Description are entered and Submit is clicked, then the text "Service request submitted successfully" is visible.

**AC-02-02** — Given the form has been submitted and the command request is in flight, when the UI is inspected before a response is received, then a submitting indicator is visible and the Submit button is not clickable.

**AC-02-03** — Given "Service request submitted successfully" is visible, when 3 seconds elapse, then the text is no longer present in the DOM.

**AC-02-04** — Given the form has been submitted, when the command request fails with a network or server error, then the text "Submission failed" is visible.

**AC-02-05** — Given a submit succeeds and no search is active, when the subsequent list request completes, then the newly submitted service request's title is present in the list.

**AC-02-06** — Given a search is active when a submit succeeds, then the search field value becomes empty and the full list endpoint is called rather than the search endpoint.

**AC-02-07** — Given the Title field is blank, when Submit is clicked, then no HTTP request is made to the command endpoint.

**AC-02-08** — Given the Description field is blank, when Submit is clicked, then no HTTP request is made to the command endpoint.

---

## US-03 — View submitted service requests

> As a user, I want to see all submitted service requests when I open the application, so that I know what is already in the system.

**AC-03-01** — Given no service requests have been submitted, when the list request completes, then the text "No service requests yet." is present in the DOM.

**AC-03-02** — Given one or more service requests have been submitted, when the list request completes, then each service request's title, description, and status are present in the DOM.

**AC-03-03** — Given the application has sent a list request that has not yet received a response, then the text "Loading" is present in the DOM.

**AC-03-04** — Given the list request fails with a network or server error, then the text "Could not load requests" is present in the DOM.

---

## US-04 — Command and query API contract

> As a developer, I want the backend API to have a stable, well-defined contract, so that clients can rely on it without inspecting the implementation.

**AC-04-01** — Given `POST /api/commands/submit-service-request` is called with `{"title":"T","description":"D"}`, then the response status is 201 and the response body is a JSON object containing exactly the fields `request_id` (string), `title` (string, value "T"), `description` (string, value "D"), `status` (string, value "submitted"), `submitted_by` (string), `created_at` (string), and `updated_at` (string).

**AC-04-02** — Given `POST /api/commands/submit-service-request` is called with header `X-User-Id: alice`, then the `submitted_by` field in the response body is `"alice"`.

**AC-04-03** — Given `POST /api/commands/submit-service-request` is called without an `X-User-Id` header, then the `submitted_by` field in the response body is `"demo-user"`.

**AC-04-04** — Given `POST /api/commands/submit-service-request` is called with a missing `title` field or a blank `title`, then the response status is 422.

**AC-04-05** — Given `POST /api/commands/submit-service-request` is called with a missing `description` field or a blank `description`, then the response status is 422.

**AC-04-06** — Given `POST /api/queries/list-service-requests` is called with `{}`, then the response status is 200 and the response body is a JSON object with a `requests` key containing an array; each element contains exactly the fields `request_id`, `title`, `description`, `status`, `submitted_by`, `created_at`, and `updated_at` as strings, and no other fields.

**AC-04-07** — Given a service request has been submitted, when `POST /api/queries/list-service-requests` is called, then the `requests` array contains an element whose field values match those returned in the submit response.

**AC-04-08** — Given service requests A and B are submitted in sequence with B submitted after A, when `POST /api/queries/list-service-requests` is called, then B appears before A in the `requests` array.

**AC-04-09** — Given a command or query endpoint is called via the nginx proxy at `http://localhost:4200/api/...`, then the response status code and response body JSON fields are the same as when calling the backend directly at `http://localhost:8080/api/...` with the same request.

---

## US-05 — Data durability

> As a developer, I want submitted service requests to survive infrastructure restarts and to have migration state tracked, so that I know the persistence layer is proven end-to-end.

**AC-05-01** — Given a service request has been submitted and then Postgres and the backend containers are restarted, when `POST /api/queries/list-service-requests` is called after the stack is healthy again, then the submitted service request still appears in the `requests` array.

**AC-05-02** — Given a fresh database, when Flyway migrations are applied, then the `schema_version` table contains exactly four rows — versions `1`, `2`, `3`, and `4` — each with `success` equal to `true`.

**AC-05-03** — Given an audit event references a `subject_id` that has no matching row in `service_requests`, then the database rejects the write and no audit event row is stored.

---

## US-06 — E2E browser roundtrip

> As a developer, I want a browser-level test that proves the full stack is wired together, so that I know Postgres, backend, nginx, and Angular are all connected correctly.

**AC-06-01** — Given the full Docker stack is running, when a browser navigates to `http://localhost:4200`, fills in a non-blank Title and Description, and clicks Submit, then within 5 seconds the submitted title is present in the list on the same page.

---

## US-07 — Clean shutdown

> As a developer, I want to stop the stack cleanly, so that no orphaned containers or data volumes are left behind.

**AC-07-01** — Given the stack is running, when `bb down` completes, then `docker compose ps --services --filter status=running` returns no output for this project.

**AC-07-02** — Given the stack has been started, when `bb down` completes, then the project Postgres data volume is absent from `docker volume ls`.

**AC-07-03** — Given an acceptance or E2E test run completes (whether passing or failing), then the project Postgres data volume is absent from `docker volume ls`.

---

## US-08 — Search submitted service requests

> As a user, I want to search submitted service requests by keyword, so that I can find relevant service requests without scrolling the full list.

**AC-08-01** — Given the Search field contains a non-blank term, when the search response is received, then the list displays only service requests that contain the term in their title or description (full-text match, case-insensitive).

**AC-08-02** — Given the Search field is blank, then the application calls the list endpoint and displays all submitted service requests.

**AC-08-03** — Given the Search field contained a term and is then cleared to blank, then the application calls the list endpoint and displays all submitted service requests.

**AC-08-04** — Given the search response is received and no submitted service requests match the term, then the text "No matching service requests." is present in the DOM.

**AC-08-05** — Given a submitted service request contains the search term in its title (matched case-insensitively), when `POST /api/queries/search-service-requests` is called with `{"query":"<term>"}`, then that service request appears in the `requests` array.

**AC-08-06** — Given a submitted service request contains the search term in its description but not in its title, when `POST /api/queries/search-service-requests` is called with `{"query":"<term>"}`, then that service request appears in the `requests` array.

**AC-08-07** — Given no submitted service requests contain the search term in their title or description, when `POST /api/queries/search-service-requests` is called with `{"query":"<term>"}`, then the response body is `{"requests":[]}`.

**AC-08-08** — Given `POST /api/queries/search-service-requests` is called without a `query` field, then the response status is 422 and the response body contains an `error` field.

**AC-08-09** — Given `POST /api/queries/search-service-requests` is called with a `query` field that is blank, contains only whitespace, or is not a string, then the response status is 422 and the response body contains an `error` field.

**AC-08-10** — Given `POST /api/queries/search-service-requests` is called with a `query` value that has leading or trailing whitespace, then the `requests` array contains the same elements as when called with the trimmed value.

**AC-08-11** — Given service request A contains the search term in its title and service request B contains the term only in its description, when `POST /api/queries/search-service-requests` is called with that term, then A appears before B in the `requests` array.

**AC-08-12** — Given `POST /api/queries/search-service-requests` returns results, then each element in the `requests` array contains exactly the fields `request_id`, `title`, `description`, `status`, `submitted_by`, `created_at`, and `updated_at` as strings, and no other fields.

---

## Quality attributes

Quality attributes are non-functional requirements that cut across all stories. Each criterion must be verified by an automated test referencing its ID.

---

### QA-01 — Operability

The system must expose a machine-readable health signal so that orchestrators can determine whether it is ready to serve traffic.

**QA-01-01** — Given the backend is running, when `GET /health` is called, then the response status is 200 and the response body is exactly `{"status":"ok"}`.

**QA-01-02** — Given `docker compose up --wait` has exited with code 0, when `POST /api/commands/submit-service-request` is called with a valid body, then the response status is 201. This verifies that the backend does not report healthy before its dependencies (database, migrations) are ready.

---

### QA-02 — Discoverability

The backend API must be self-describing so that a developer can understand and call every endpoint without reading source code.

**QA-02-01** — Given the backend is running, when `GET /openapi.json` is called, then the response status is 200 and the `openapi` field in the response body is a string beginning with `"3."`.

**QA-02-02** — Given the backend is running, when `GET /openapi.json` is called, then the document contains the path `POST /api/commands/submit-service-request` with a request body schema whose `required` array is exactly `["title", "description"]`.

**QA-02-03** — Given the backend is running, when `GET /openapi.json` is called, then the submit endpoint entry includes a request body example with non-blank `title` and `description` string values.

**QA-02-04** — Given the backend is running, when `GET /openapi.json` is called, then the document contains the path `POST /api/queries/list-service-requests`.

**QA-02-05** — Given the backend is running, when `GET /openapi.json` is called, then the document contains the path `POST /api/queries/search-service-requests` with a request body schema whose `required` array is exactly `["query"]`.

**QA-02-06** — Given the backend is running, when `GET /openapi.json` is called, then the search endpoint entry includes a request body example with a non-blank `query` string value.

**QA-02-07** — Given the backend is running, when `GET /swagger-ui` is called, then the response status is 200.

---

### QA-03 — Auditability

Every state-changing command must produce a complete, accurate record of who performed the action, what action was performed, and which resource was affected. The record must be written in the same database transaction as the command so it cannot be bypassed or lost.

**QA-03-01** — Given `POST /api/commands/submit-service-request` is called with header `X-User-Id: alice` and succeeds, then exactly one audit event row exists in the database with actor `"alice"`, action `"submit-service-request"`, and `subject_id` equal to the `request_id` returned in the response body.

**QA-03-02** — Given `POST /api/commands/submit-service-request` is called without an `X-User-Id` header and succeeds, then exactly one audit event row exists in the database with actor `"demo-user"`, action `"submit-service-request"`, and `subject_id` equal to the `request_id` returned in the response body.

**QA-03-03** — Given a service request insert and audit event insert execute within a single database transaction, when the audit event insert fails (e.g. due to a constraint violation), then the transaction is rolled back and neither the service request row nor the audit event row is present in the database.

**QA-03-04** — Given `POST /api/commands/submit-service-request` is called twice in sequence, then exactly two audit event rows are present in the database, one per submission.

---

### QA-04 — Performance

The system must remain responsive and error-free under concurrent load. A request is successful if it receives an HTTP 2xx response; a timeout, connection error, or 5xx counts as a failure.

**QA-04-01** — Given the `submit-and-list` scenario runs against the live stack (each virtual user executes: list service requests → pause 1 s → submit a service request → pause 1 s → list service requests again; 10 users ramped linearly over 30 s), then the p95 response time is below 1000 ms, the p99 response time is below 2000 ms, and the success rate is above 99%.

**QA-04-02** — Given the `browse-and-search` scenario runs against the live stack (each virtual user executes: list service requests → pause 1 s → search for a known term; 20 users ramped linearly over 30 s), then the p95 response time is below 1000 ms, the p99 response time is below 2000 ms, and the success rate is above 99%.

---

### QA-05 — Security

The system must not be exploitable through its input surface. Authentication and authorisation are out of scope; the criteria below address input handling and information disclosure.

**QA-05-01** — Given `POST /api/commands/submit-service-request` is called with a `title` or `description` containing potentially dangerous content (e.g. SQL metacharacters such as `'; DROP TABLE service_requests; --` or script tags such as `<script>alert(1)</script>`), then the response status is 201, and when the submitted service request is subsequently retrieved via the list endpoint, the returned `title` or `description` value is byte-for-byte equal to what was submitted.

**QA-05-02** — Given a request to the backend produces a 422 validation error response, then the response body is a JSON object and does not contain a stack trace, a file system path, a SQL statement, or a database connection string. (Scope is limited to validation errors; 404, 405, and 5xx responses are not yet covered by a JSON error handler.)

**QA-05-03** — *Not yet implemented.* Given `POST /api/commands/submit-service-request` is called with a request body exceeding a defined maximum byte size, then the backend returns 413 before processing the request. No size limit is currently configured.
