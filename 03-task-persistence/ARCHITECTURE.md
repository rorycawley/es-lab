# Architecture: 03-task-persistence

## Runtime

```
                    ┌──────────────────────── Docker Compose ──────────────────────────────────┐
                    │                                                                            │
  localhost:4200 ───┼──▶  frontend  (nginx:alpine)                         :80                 │
                    │     │                                                                      │
                    │     │  /api/*        proxy_pass → preserves /api/ prefix ──────────────────┼──▶  backend  (eclipse-temurin:21-jre)  :8080
                    │     │  /*.js,*.css   static, Cache-Control: immutable                     │     │
                    │     │  /*            try_files $uri /index.html                           │     │  POST /api/commands/submit-service-request
                    │     │                                                                      │     │  POST /api/queries/list-service-requests
                    │     │                                                                      │     │  POST /api/queries/search-service-requests
                    │     └── depends_on: backend condition: service_healthy                    │     │  GET  /health
                    │                                                                            │     │  GET  /openapi.json
  localhost:8080 ───┼────────────────────────────────────────────────────────────────────────────┼──▶  │  GET  /swagger-ui
  (direct)          │                                                                            │     │
                    │                                                                            │     └── depends_on: postgres condition: service_healthy
                    │                                                                            │           │
                    │                                                                            │           ▼
                    │                                                                            │     postgres  (postgres:18.4-alpine)  :5432
  localhost:5432 ───┼────────────────────────────────────────────────────────────────────────────┼──▶  tables: service_requests, audit_events
  (direct)          │                                                                            │     index: service_requests_search_vector_idx
                    │                                                                            │     named volume: postgres-data
                    │                                                                            │     healthcheck: pg_isready -U taskuser -d taskdb
                    └────────────────────────────────────────────────────────────────────────────┘
```

On startup the backend runs Flyway before binding to port 8080. Migrations are SQL files in `resources/database/migrations/`; only outstanding ones are applied and recorded in Flyway's `schema_version` metadata table. Postgres stores data in the `postgres-data` Docker volume while the stack is running. `bb down`, `bb reset`, and Docker-backed test tasks remove that volume, so each run starts from a clean local database. Search uses a generated weighted `tsvector` column and a GIN index in Postgres.

The nginx proxy preserves the full path when forwarding to the backend. `proxy_pass http://backend:8080` (no trailing slash) means no URI rewriting — a browser `POST /api/commands/submit-service-request` reaches the backend at exactly `POST /api/commands/submit-service-request`. Adding a trailing slash (`http://backend:8080/`) would strip the `/api/` prefix, causing 404s.

---

## Command and query flow

```
Browser
  │
  │  POST /api/commands/submit-service-request  {"title":"…","description":"…"}
  ▼
nginx (frontend container)
  │  proxy_pass http://backend:8080
  ▼
submit-service-request-handler (commands.clj)
  │  1. read X-User-Id header (default "demo-user")
  │  2. validate: title and description must be non-blank strings → 422 if not
  │  3. open JDBC transaction via transact!
  │     3a. call ServiceRequestPort/save!  → saved record
  │     3b. call AuditPort/record!         → nil   (rolls back save if this fails)
  │     3c. commit
  │  4. return {:status 200 :body saved}
  │
  ├─▶  PostgresServiceRequestPort (db/postgres.clj)
  │      INSERT INTO service_requests ... RETURNING *
  │      → snake_case map; UUIDs and timestamps stringified
  │
  └─▶  PostgresAuditPort (db/postgres.clj)
         INSERT INTO audit_events ...

Browser
  │
  │  POST /api/queries/list-service-requests  {}
  ▼
list-service-requests-handler (queries.clj)
  │  call ServiceRequestPort/list-all → [{…}, …]
  │  return {:status 200 :body {:requests […]}}
  │
  └─▶  PostgresServiceRequestPort (db/postgres.clj)
         SELECT request_id, submitted_by, title, description, status, created_at, updated_at
         FROM service_requests
         ORDER BY created_at DESC
         → vector of snake_case maps

Browser
  │
  │  POST /api/queries/search-service-requests  {"query":"printer"}
  ▼
search-service-requests-handler (queries.clj)
  │  1. validate: query must be a non-blank string → 422 if not
  │  2. trim query
  │  3. call ServiceRequestPort/search → [{…}, …]
  │  4. return {:status 200 :body {:requests […]}}
  │
  └─▶  PostgresServiceRequestPort (db/postgres.clj)
         WHERE search_vector @@ plainto_tsquery('english', ?)
         ORDER BY ts_rank(...) DESC, created_at DESC
         → vector of snake_case maps with rank
```

---

## Ports and adapters

```
domain layer                         adapter layer
────────────────────────────────     ───────────────────────────────────
service_requests/port.clj            db/postgres.clj
  (defprotocol ServiceRequestPort      PostgresServiceRequestPort [ds]
    (save!    [port request])            → next.jdbc/execute-one! INSERT
    (list-all [port])                    → next.jdbc/execute! SELECT
    (search   [port query]))             → next.jdbc/execute! full-text search SELECT

audit/port.clj                         PostgresAuditPort [ds]
  (defprotocol AuditPort               → next.jdbc/execute-one! INSERT
    (record! [port event]))

                                     test_support.clj
                                       InMemoryServiceRequestPort [!store]
                                         → atom; returns same snake_case shape
                                       InMemoryAuditPort [!store]
                                         → atom; returns nil
```

The command and query handlers import only the protocol namespaces — they never see `next.jdbc` or SQL. `make-router` in `routes.clj` accepts a `ctx` map `{:service-request-port … :audit-port … :transact! …}` and closes over it when building handlers. This is why `make-router` is a function, not a `def`. The `:transact!` key is a function `(fn [f] ...)` that opens a JDBC transaction and calls `f` with transactional port instances — so `save!` and `record!` are atomic. In tests, `:transact!` is a no-op wrapper that passes the in-memory ports through directly.

`audit_events.subject_id` has a foreign key to `service_requests.request_id`. That makes the audit log request-scoped and prevents orphan audit rows if code ever writes audit data outside the command handler path. The tradeoff is intentional: future request deletion or archival features must handle audit rows explicitly instead of deleting service requests in isolation.

The `service_requests.search_vector` column is generated by Postgres from `title` and `description`, with title weighted higher than description. The GIN index supports the `search` query without pushing tokenisation or ranking logic into Clojure.

---

## Image builds

```
frontend                                    backend
────────────────────────────────────────    ────────────────────────────────────────
Stage 1: node:22-alpine                     Stage 1: clojure:temurin-21-tools-deps-alpine
  COPY package.json package-lock.json         COPY deps.edn build.clj
  RUN npm ci                    ← cached      RUN clojure -P              ← cached
  COPY . .       (src after deps)             COPY src resources
  RUN ng build --configuration=production     RUN clojure -T:build uber
  → dist/frontend/browser/                   → target/app.jar

Stage 2: nginx:alpine                       Stage 2: eclipse-temurin:21-jre-alpine
  COPY dist/frontend/browser/ /usr/share/     RUN addgroup/adduser app    ← non-root
       nginx/html/                            COPY target/app.jar app.jar
  COPY nginx/default.conf                     ENTRYPOINT java -jar app.jar
```

---

## Angular internals

```
main.ts
  bootstrapApplication(AppComponent, {
    providers: [
      provideZonelessChangeDetection(),   ← no zone.js; re-renders on signal change only
      provideHttpClient(),
    ]
  })

AppComponent
  formModel:     signal({ title, description })   ← source of truth for form values
  submitForm:    form(formModel)                  ← Signal Forms FieldTree; [formField] binds to <input>

  loadTrigger:   signal(0)                        ← incremented after every successful submit
  searchQuery:   signal("")                       ← source of truth for active search input
  requests:      toSignal(
                   combineLatest([
                     toObservable(loadTrigger),
                     toObservable(searchQuery).pipe(
                       map(trim),
                       distinctUntilChanged(),
                       debounce(300ms when nonblank),
                     )
                   ]).pipe(
                     switchMap(([, query]) → {
                       request$ =
                         query
                           ? http.post('/api/queries/search-service-requests', {query})
                           : http.post('/api/queries/list-service-requests', {})

                       return request$.pipe(
                         map(r → r.requests),
                         catchError(() → []),
                         startWith(null),          ← null = loading state
                       )
                     })
                   ), { initialValue: null }
                 )

  submitState:   signal<'idle'|'submitting'|'success'|'error'>('idle')

  submit():
    1. read formModel()
    2. guard: skip if title or description is blank
    3. set submitState → 'submitting'
    4. POST /api/commands/submit-service-request
       next: submitState → 'success', clear formModel, loadTrigger++
       error: submitState → 'error'
```

---

## Test layers

```
Layer              Tool                       Docker   What it proves
─────────────────  ─────────────────────────  ───────  ─────────────────────────────────────────────────
backend unit       kaocha (clojure -M:test)   no       command validation, query response shape
backend integ.     kaocha + live Jetty         no       HTTP routes, JSON, middleware, OpenAPI, Swagger UI
backend adapter    kaocha + Testcontainers     yes      Flyway migrations and Postgres adapter behaviour
frontend unit      Jest + jsdom                no       all UI states, submit flow, error handling
HTTP acceptance    Babashka http-client        yes      nginx proxy, full roundtrip, Postgres persistence and restart durability
E2E browser        Playwright (Chromium)       yes      form submit + list render in a real browser
```

`bb test` runs the four top-level test tasks sequentially: backend, frontend, acceptance, and e2e. The backend task covers three layers internally: unit, HTTP integration, and Postgres adapter tests. `test:acceptance` manages Docker inside a `use-fixtures :once` fixture; `test:e2e` manages Docker in `bb.edn`. Both Docker-backed stack tests remove the project Postgres volume before and after running, so persisted test data cannot affect later runs. Docker starts twice when running `bb test` — intentional so every task is independently runnable.

For local backend development, `bb db:up` starts only Postgres. Domain and HTTP integration tests use in-memory ports and need no Docker; Postgres adapter tests use Testcontainers and require Docker.
