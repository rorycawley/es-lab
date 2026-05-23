# 03-task-persistence

## What this demonstrates

A user submits a service request through an Angular 21 SPA. The Clojure backend validates it, persists it to Postgres via a Migratus migration, writes an audit record, and serves it back via a read query. The frontend reloads the list after each submission.

Introduces: durable Postgres persistence, Migratus migrations, intentful POST-only API (commands and queries), ports-and-adapters at the persistence boundary, and a simple audit log.

## Architecture

Three Docker services: Postgres (database), a Clojure backend (commands and queries), and an Angular SPA served by nginx. The backend runs Migratus on startup to apply outstanding migrations before accepting requests. The domain layer calls persistence through protocols; JDBC is confined to the adapter layer.

| Role | Choice | Rationale |
|---|---|---|
| Frontend framework | Angular 21 (standalone, zoneless) | Same base as 02-hello-frontend |
| Styling | Tailwind CSS | Utility-first; detected automatically by Angular's esbuild builder |
| Frontend server | nginx | Serves static files; proxies `/api/` to the backend |
| API design | POST-only JSON (commands and queries) | Intentful — every call names its operation; uniform auth and audit surface |
| Backend framework | Ring + Reitit | Same as 01/02 |
| Persistence | next.jdbc + Postgres | Minimal JDBC wrapper; `as-unqualified-lower-maps` gives snake_case keys matching column names |
| Migrations | Migratus | Runs on startup; migration files in `resources/migrations/` |
| Ports and adapters | Clojure protocols | Domain calls `ServiceRequestPort` and `AuditPort`; JDBC adapter implements both |
| Audit log | Separate `audit_events` table | Records every command with actor, action, subject, and metadata |
| Unit test runner | Jest + jest-preset-angular | Frontend unit tests with mocked HTTP |
| Acceptance test layer | Babashka + http-client | HTTP tests against the live stack |
| E2E test runner | Playwright (Chromium) | Full-browser roundtrip against the live stack |

## API design

All endpoints use `POST` with a JSON body. No `GET`, `PUT`, `PATCH`, or `DELETE` anywhere.

| Method | URL | Operation |
|---|---|---|
| POST | `/api/commands/submit-service-request` | Record a new service request |
| POST | `/api/queries/list-service-requests` | Return all service requests |

## How to run it

### 1. Install mise

```sh
curl https://mise.run | sh
```

### 2. Install declared tools (bootstrap only)

```sh
mise install   # installs node, babashka, java, clojure, and other tools
```

### 3. Run everything

```sh
bb all   # trust config + install npm packages + install Playwright + build image + run all tests
```

`bb all` calls `mise trust`, `mise install`, `npm install`, and `npx playwright install chromium` internally — after the first bootstrap you only ever need `bb all`.

### 4. Start and stop the stack manually

```sh
bb up    # build images and start the full stack (postgres + backend + frontend)
bb down  # stop the stack
```

### 5. Local backend development (Postgres only)

```sh
bb db:up    # start only the Postgres container
bb db:down  # stop only the Postgres container
```

With Postgres running, start the backend directly:

```sh
cd backend && clojure -M -m es-lab.task-persistence.core
```

## Endpoints

After `bb up` the stack is available at:

| URL | Description |
|---|---|
| `http://localhost:4200` | Angular app — submit and view service requests |
| `http://localhost:4200/api/commands/submit-service-request` | Submit command via nginx proxy |
| `http://localhost:4200/api/queries/list-service-requests` | List query via nginx proxy |
| `http://localhost:8080/health` | Backend health directly |
| `http://localhost:8080/openapi.json` | OpenAPI 3.0 spec |
| `http://localhost:8080/swagger-ui` | Interactive API browser |

## What to look at

- [backend/src/es_lab/task_persistence/service_requests/port.clj](backend/src/es_lab/task_persistence/service_requests/port.clj) — `ServiceRequestPort` protocol (the boundary between domain and JDBC)
- [backend/src/es_lab/task_persistence/audit/port.clj](backend/src/es_lab/task_persistence/audit/port.clj) — `AuditPort` protocol
- [backend/src/es_lab/task_persistence/service_requests/commands.clj](backend/src/es_lab/task_persistence/service_requests/commands.clj) — submit-service-request handler: validates, persists, audits
- [backend/src/es_lab/task_persistence/service_requests/queries.clj](backend/src/es_lab/task_persistence/service_requests/queries.clj) — list-service-requests handler
- [backend/src/es_lab/task_persistence/db/postgres.clj](backend/src/es_lab/task_persistence/db/postgres.clj) — JDBC adapter implementing both ports
- [backend/src/es_lab/task_persistence/routes.clj](backend/src/es_lab/task_persistence/routes.clj) — `make-router` function: injects ports into handlers
- [backend/src/es_lab/task_persistence/core.clj](backend/src/es_lab/task_persistence/core.clj) — wires Migratus, datasource, ports, and Jetty
- [backend/resources/migrations/](backend/resources/migrations/) — Migratus SQL migration files
- [backend/test/es_lab/task_persistence/test_support.clj](backend/test/es_lab/task_persistence/test_support.clj) — in-memory port implementations for testing
- [backend/test/es_lab/task_persistence/integration_test.clj](backend/test/es_lab/task_persistence/integration_test.clj) — real Jetty + in-memory ports (no Docker)
- [frontend/src/app/app.component.ts](frontend/src/app/app.component.ts) — Signal Forms, reactive HTTP, submit and list state
- [frontend/src/app/app.component.html](frontend/src/app/app.component.html) — Tailwind-styled submit form and request list
- [frontend/src/app/app.component.spec.ts](frontend/src/app/app.component.spec.ts) — Jest unit tests with mocked HTTP
- [frontend/nginx/default.conf](frontend/nginx/default.conf) — `/api/` proxy and Angular routing
- [docker-compose.yml](docker-compose.yml) — three services: postgres → backend → frontend, each with healthchecks
- [acceptance/app_test.clj](acceptance/app_test.clj) — HTTP acceptance tests including submit-then-list roundtrip
- [frontend/e2e/app.spec.ts](frontend/e2e/app.spec.ts) — Playwright E2E browser test

## Quality attributes demonstrated

| Attribute | How |
|---|---|
| Portability | Runs with `docker compose up` — no local runtimes required |
| Operability | Backend waits for Postgres healthcheck; frontend waits for backend healthcheck; Migratus auto-applies migrations on startup |
| Maintainability | Domain handlers are pure functions of a `ctx` map; ports isolate them from JDBC; in-memory adapters make all domain tests Docker-free |
| Discoverability | Backend OpenAPI spec and Swagger UI available at runtime |
| Auditability | Every command writes to `audit_events` with actor, action, subject, and metadata |

## Fitness criteria

- `docker compose up` completes without error on a machine with only Docker installed
- `POST /api/commands/submit-service-request` with `{"title":"T","description":"D"}` returns `200 OK` with a `request_id`
- `POST /api/queries/list-service-requests` with `{}` returns `200 OK` with a `requests` array
- Submitting a request then listing returns the submitted request in the list
- The Angular app shows a submitted request title in the list after form submission
- `docker compose down` exits cleanly with no containers remaining

## Running the tests

```sh
bb test                # all tests
bb test:backend        # backend unit, integration, and adapter tests (adapter tests require Docker)
bb test:frontend       # frontend unit tests with Jest (no Docker)
bb test:acceptance     # acceptance tests against the live stack (HTTP)
bb test:e2e            # Playwright browser tests against the live stack
```

## Code quality

```sh
bb lint        # lint backend with clj-kondo
bb fmt:check   # check backend formatting with cljfmt
bb fmt:fix     # reformat backend with cljfmt
bb clean       # remove build artefacts
```

## Security design notes

### What could go wrong?

The backend accepts arbitrary JSON bodies and writes to Postgres. A misconfigured parser could allow oversized payloads. The `X-User-Id` header is trusted from the client — any caller can impersonate any user.

### What design choices reduce the risk?

The command handler validates that title and description are non-blank strings before touching the database. The ports-and-adapters boundary means query construction is confined to one adapter file. `save!` and `audit/record!` execute inside a single JDBC transaction — if the audit write fails, the service request is rolled back.

### What is deliberately not solved yet?

Authentication (the `X-User-Id` header is a stand-in), authorisation, TLS, CSRF protection, rate limiting, input length limits, request body size limits (no explicit limit is configured), Postgres credentials rotation.

## Production notes

- Replace the hardcoded `DATABASE_URL` default with a secrets manager reference
- The backend port 8080 is exposed directly to the host for development; in production remove that binding so the backend is only reachable through the frontend's network
- Add a connection pool (HikariCP) before going to production — `next.jdbc/get-datasource` creates an unpooled datasource

## What is not proven yet

- Authentication and session management
- Row-level authorisation (users seeing only their own requests)
- Pagination and filtering of the request list
- Request status transitions (approved, rejected, closed)
- Real-time updates (SSE or WebSocket)
- HTTPS / TLS termination

## What I learned

- `make-router` must be a function (not a `def`) when it takes a `ctx` map — the ports are chosen at startup, not at compile time
- Migratus configuration requires passing the datasource directly: `{:store :database :migration-dir "migrations" :db {:datasource ds}}`
- `next.jdbc/execute-one!` with `as-unqualified-lower-maps` returns a snake_case map that matches Postgres column names exactly — no renaming needed in the domain layer
- `java.sql.Timestamp` must be converted to a string before JSON serialisation: `.toInstant().toString()` gives ISO-8601
- `java.util.UUID` must also be converted: `.toString()` — jsonista does not serialise UUIDs automatically
- JSONB columns need explicit casting in SQL: `?::jsonb`; `jsonista/write-value-as-string` serialises the Clojure map before the cast
- `pg_isready -U taskuser -d taskdb` is the minimal Postgres healthcheck — `wget` cannot be used because Postgres does not speak HTTP
- `loadTrigger` as an integer signal lets the list reload after every submit, even if the same form values are submitted twice in a row
- Backend integration tests with real Jetty and in-memory ports are fast (no Docker) and still exercise routing, middleware, and handler wiring
- `audit/record!` is called inside the command handler with no error handling — if audit fails the command fails; audit is mandatory, not optional
