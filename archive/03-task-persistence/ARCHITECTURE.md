# Architecture: 03-task-persistence

## C4 Level 2 — Containers

```
 ┌──────────────────────┐     ┌────────────────────────────────────────────────────────────────────────────────────────────┐
 │                      │     │                                 Task Persistence [System]                                   │
 │ <<Person>>           │     │                                                                                             │
 │ User                 │     │  ┌───────────────────────────────────┐         ┌───────────────────────────────────┐        │
 │                      │     │  │ <<Container>>                     │         │ <<Container>>                     │        │
 │ Submits and views    │HTTP │  │ Frontend                          │ HTTP    │ Backend                           │        │
 │ service requests     │────►│  │ [nginx:alpine]  :4200             │────────►│ [eclipse-temurin:21-jre]  :8080   │        │
 │ via a web browser    │◄────│  │                                   │◄────────│                                   │        │
 │                      │JSON │  │ Serves the Angular 21 SPA.        │ JSON    │ Clojure, Ring + Reitit.           │        │
 └──────────────────────┘     │  │ Proxies POST /api/* to the        │         │ Handles POST commands and         │        │
                               │  │ backend without rewriting          │         │ queries. Connects to a fully      │        │
                               │  │ the path.                         │         │ migrated schema.                  │        │
                               │  └───────────────────────────────────┘         └──────────────────┬────────────────┘        │
                               │                                                                     │ JDBC :5432              │
                               │                                                  ┌──────────────────┴────────────────┐        │
                               │                                                  │ <<Container>>                     │        │
                               │                                                  │ Migrate (one-shot init)           │        │
                               │                                                  │ [flyway/flyway:12-alpine]         │        │
                               │                                                  │                                   │        │
                               │                                                  │ Applies SQL migrations and exits. │        │
                               │                                                  │ Runs before backend starts.       │        │
                               │                                                  └──────────────────┬────────────────┘        │
                               │                                                                     │ JDBC :5432              │
                               │                                                                     ▼                        │
                               │                                                  ┌───────────────────────────────────┐        │
                               │                                                  │ <<Container>>                     │        │
                               │                                                  │ Database                          │        │
                               │                                                  │ [postgres:18.4-alpine]  :5432     │        │
                               │                                                  │                                   │        │
                               │                                                  │ Stores service_requests and       │        │
                               │                                                  │ audit_events. GIN index for       │        │
                               │                                                  │ weighted full-text search.        │        │
                               │                                                  └───────────────────────────────────┘        │
                               └────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Containers

| Container | Technology | Port | Responsibility |
|---|---|---|---|
| Frontend | nginx:alpine | 4200 | Serves the Angular 21 SPA; proxies `/api/*` to the backend without rewriting the path |
| Backend | Clojure, eclipse-temurin:21-jre | 8080 | Ring + Reitit HTTP server; handles POST commands and queries |
| Migrate | flyway/flyway:12-alpine | — | Applies outstanding SQL migrations and exits; runs before the backend starts |
| Database | postgres:18.4-alpine | 5432 | Stores service requests and audit events; GIN index for weighted full-text search |

Docker Compose starts containers in dependency order: postgres → migrate → backend → frontend. `migrate` mounts the SQL files from `backend/resources/database/migrations/`, runs all outstanding migrations against postgres, and exits. The backend starts only after `migrate` completes successfully, so it always connects to a fully migrated schema. Data lives in a named volume (`postgres-data`) so submitted requests survive container restarts. `bb down` and both Docker-backed test tasks remove that volume to prevent data leaking across runs.

---

## API

All business operations use `POST`. `GET` is reserved for operational endpoints.

| Method | Path | Operation |
|---|---|---|
| POST | `/api/commands/submit-service-request` | Validate and persist a new service request; write an audit event in the same transaction |
| POST | `/api/queries/list-service-requests` | Return all service requests, most recent first |
| POST | `/api/queries/search-service-requests` | Return ranked full-text search results |
| GET | `/health` | Health check |
| GET | `/openapi.json` | OpenAPI 3.0 spec |
| GET | `/swagger-ui` | Interactive API browser |

The nginx proxy uses `proxy_pass http://backend:8080` with no trailing slash, so the full path — including the `/api/` prefix — is forwarded to the backend unchanged.

---

## Command flow

`POST /api/commands/submit-service-request` with `{"title":"…","description":"…"}`:

1. Read `X-User-Id` header (default `"demo-user"`)
2. Validate: `title` and `description` must be non-blank strings → `422` if not
3. Open a JDBC transaction via `transact!`
   - `ServiceRequestPort/save!` — inserts a row and returns the saved record
   - `AuditPort/record!` — inserts an audit row in the same transaction; if this fails, `save!` rolls back
4. Return `201` with the saved record

`transact!` binds both port instances to the same JDBC connection, making `save!` and `record!` atomic.

---

## Query flow

`POST /api/queries/list-service-requests` with `{}`:

Returns all rows from `service_requests` ordered by `created_at DESC, request_id DESC` (UUIDv7 tie-breaker for same-millisecond inserts). Response body: `{"requests":[…]}`.

`POST /api/queries/search-service-requests` with `{"query":"…"}`:

1. Validate: `query` must be a non-blank string → `422` if not
2. Trim the query
3. Run `plainto_tsquery('english', ?)` against the generated `search_vector` column
4. Rank by `ts_rank` descending, then `created_at` descending
5. Strip `rank` and `search_vector` from each result before returning

`search_vector` is a Postgres generated column: `title` is weighted `A`, `description` is weighted `B`. A GIN index makes the query fast. Ranking and tokenisation stay in Postgres — no text processing in Clojure.

---

## Ports and adapters

The command and query handlers import only protocol namespaces. They never see `next.jdbc` or SQL.

```
domain                                   adapter
──────────────────────────────────────   ──────────────────────────────────────────────
service_requests/port.clj               db/postgres.clj
  (defprotocol ServiceRequestPort         PostgresServiceRequestPort [ds]
    (save!    [port request])               INSERT INTO service_requests … RETURNING *
    (list-all [port])                       SELECT … ORDER BY created_at DESC, request_id DESC
    (search   [port query]))                WHERE search_vector @@ plainto_tsquery …

audit/port.clj                           PostgresAuditPort [ds]
  (defprotocol AuditPort                   INSERT INTO audit_events …
    (record! [port event]))

                                         test_support.clj
                                           InMemoryServiceRequestPort [!store]  ← atom
                                           InMemoryAuditPort [!store]           ← atom
```

`make-router` in `routes.clj` takes a `ctx` map `{:service-request-port … :audit-port … :transact! …}` and closes over it. In production, `:transact!` opens a JDBC transaction and supplies transactional port instances. In tests, `:transact!` is a pass-through that injects in-memory ports directly — no Docker needed for domain or HTTP integration tests.

`audit_events.subject_id` has a foreign key to `service_requests.request_id`, making the audit log request-scoped and preventing orphan audit rows.

---

## Frontend reactive model

The component is zoneless (no `zone.js`). All state flows through signals; HTTP is driven reactively.

- `loadTrigger` (integer signal) increments after each successful submit, forcing a reload even if the same values are submitted twice in a row
- `searchQuery` (string signal) drives debounced search; when blank, the full list query runs instead
- `requests` derives from `combineLatest([loadTrigger, searchQuery])` via `switchMap` — any change cancels the in-flight request and starts a new one
- On submit success: if a search is active, `searchQuery` is cleared (which triggers a full list reload via `combineLatest`); otherwise `loadTrigger` increments
- `submitState` resets from `'success'` to `'idle'` after 3 seconds, hiding the success message
