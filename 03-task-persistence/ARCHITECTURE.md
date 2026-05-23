# Architecture: 03-task-persistence

## Runtime

```
                    ┌──────────────────────── Docker Compose ──────────────────────────────────┐
                    │                                                                            │
  localhost:4200 ───┼──▶  frontend  (nginx:alpine)                         :80                 │
                    │     │                                                                      │
                    │     │  /api/*        proxy_pass → strips /api/ prefix ────────────────────┼──▶  backend  (eclipse-temurin:21-jre)  :8080
                    │     │  /*.js,*.css   static, Cache-Control: immutable                     │     │
                    │     │  /*            try_files $uri /index.html                           │     │  POST /api/commands/submit-service-request
                    │     │                                                                      │     │  POST /api/queries/list-service-requests
                    │     └── depends_on: backend condition: service_healthy                    │     │  GET  /health
                    │                                                                            │     │  GET  /openapi.json
  localhost:8080 ───┼────────────────────────────────────────────────────────────────────────────┼──▶  │  GET  /swagger-ui
  (direct)          │                                                                            │     │
                    │                                                                            │     └── depends_on: postgres condition: service_healthy
                    │                                                                            │           │
                    │                                                                            │           ▼
                    │                                                                            │     postgres  (postgres:16-alpine)  :5432
  localhost:5432 ───┼────────────────────────────────────────────────────────────────────────────┼──▶  tables: service_requests, audit_events
  (direct)          │                                                                            │     healthcheck: pg_isready -U taskuser -d taskdb
                    └────────────────────────────────────────────────────────────────────────────┘
```

On startup the backend runs `migratus/migrate` before binding to port 8080. Migrations are SQL files in `resources/migrations/`; only outstanding ones are applied.

The nginx proxy strips the `/api/` prefix before forwarding: a browser `POST /api/commands/submit-service-request` reaches the backend as `POST /api/commands/submit-service-request` (the full path is preserved because the proxy target has no trailing-slash path component).

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
  │  3. call ServiceRequestPort/save!  → saved record
  │  4. call AuditPort/record!         → nil
  │  5. return {:status 200 :body saved}
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
         SELECT * FROM service_requests ORDER BY created_at DESC
         → vector of snake_case maps
```

---

## Ports and adapters

```
domain layer                         adapter layer
────────────────────────────────     ───────────────────────────────────
service_requests/port.clj            db/postgres.clj
  (defprotocol ServiceRequestPort      PostgresServiceRequestPort [ds]
    (save!    [port request])            → next.jdbc/execute-one! INSERT
    (list-all [port]))                   → next.jdbc/execute! SELECT

audit/port.clj                         PostgresAuditPort [ds]
  (defprotocol AuditPort               → next.jdbc/execute-one! INSERT
    (record! [port event]))

                                     test_support.clj
                                       InMemoryServiceRequestPort [!store]
                                         → atom; returns same snake_case shape
                                       InMemoryAuditPort [!store]
                                         → atom; returns nil
```

The command and query handlers import only the protocol namespaces — they never see `next.jdbc` or SQL. `make-router` in `routes.clj` accepts a `ctx` map `{:service-request-port … :audit-port …}` and closes over it when building handlers. This is why `make-router` is a function, not a `def`.

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
  requests:      toSignal(
                   toObservable(loadTrigger).pipe(
                     switchMap(() →
                       http.post('/api/queries/list-service-requests', {}).pipe(
                         map(r → r.requests),
                         catchError(() → []),
                         startWith(null),          ← null = loading state
                       )
                     )
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
frontend unit      Jest + jsdom                no       all UI states, submit flow, error handling
HTTP acceptance    Babashka http-client        yes      nginx proxy, full roundtrip, Postgres persistence
E2E browser        Playwright (Chromium)       yes      form submit + list render in a real browser
```

`bb test` runs all five sequentially. `test:acceptance` manages Docker inside a `use-fixtures :once` fixture; `test:e2e` manages Docker in `bb.edn`. Docker starts twice when running `bb test` — intentional so every task is independently runnable.

For local backend development without Docker, `bb db:up` starts only Postgres. Backend tests use in-memory ports and need no Docker at all.
