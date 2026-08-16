# 02-hello-frontend

## What this demonstrates

An Angular 21 SPA served from nginx calls a Clojure backend and displays the health status using Tailwind CSS. Both services run under `docker compose up`. This is the base shape that all subsequent frontend projects extend.

## Architecture

Two Docker services: the Angular app built with Node and served by nginx; the Clojure backend copied from `01-hello-backend`.

| Role | Choice | Rationale |
|---|---|---|
| Frontend framework | Angular 21 (standalone, zoneless) | Zoneless by default removes zone.js (~34 kB) and forces Signals for all reactive updates |
| Styling | Tailwind CSS | Utility-first; detected automatically by Angular's esbuild builder |
| Frontend server | nginx | Serves static files; proxies `/api/` to the backend; handles Angular routing via `try_files` |
| Backend URL | nginx reverse proxy (`/api/`) | Default path `/api/health` is same-origin — no CORS, no backend URL baked into the image; the endpoint input is a debug control for testing other paths |
| Unit test runner | Jest + jest-preset-angular | Headless; no browser dependency; simpler CI path than Karma |
| Acceptance test layer | Babashka + http-client | HTTP tests against the live stack; verifies the full service wiring without a browser |
| E2E test runner | Playwright (Chromium) | Proves the full stack renders the health message in a real browser |
| HTTP contract | Ring | Same as 01 |
| Routing | Reitit | Same as 01 |

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
bb up    # build images and start the stack
bb down  # stop the stack
```

## Endpoints

After `bb up` the stack is available at:

| URL | Description |
|---|---|
| `http://localhost:4200` | Angular app — shows backend health status |
| `http://localhost:4200/api/health` | Backend health via nginx proxy |
| `http://localhost:8080/health` | Backend health directly |
| `http://localhost:8080/openapi.json` | OpenAPI 3.0 spec |
| `http://localhost:8080/swagger-ui` | Interactive API browser |

## What to look at

- [frontend/angular.json](frontend/angular.json) — `polyfills: []` (removes zone.js) and esbuild builder configuration
- [frontend/src/main.ts](frontend/src/main.ts) — Angular bootstrap: `provideZonelessChangeDetection()` and `provideHttpClient()`
- [frontend/src/app/app.component.ts](frontend/src/app/app.component.ts) — Signal Forms, reactive HTTP call, and health state
- [frontend/src/app/app.component.html](frontend/src/app/app.component.html) — Tailwind-styled health display
- [frontend/src/app/app.component.spec.ts](frontend/src/app/app.component.spec.ts) — Jest unit tests with mocked HTTP
- [frontend/nginx/default.conf](frontend/nginx/default.conf) — `/api/` proxy and Angular routing
- [frontend/Dockerfile](frontend/Dockerfile) — two-stage build: node → nginx
- [backend/src/es_lab/hello_backend/routes.clj](backend/src/es_lab/hello_backend/routes.clj) — health endpoint
- [docker-compose.yml](docker-compose.yml) — two services wired together
- [acceptance/app_test.clj](acceptance/app_test.clj) — HTTP acceptance tests against the live stack
- [frontend/e2e/app.spec.ts](frontend/e2e/app.spec.ts) — Playwright E2E browser test
- [frontend/playwright.config.ts](frontend/playwright.config.ts) — Playwright configuration

## Quality attributes demonstrated

| Attribute | How |
|---|---|
| Portability | Runs with `docker compose up` — no local runtimes required |
| Operability | Frontend depends on backend healthcheck before starting |
| Maintainability | Angular component derives its UI from three private signals (`endpointModel`, `checkTrigger`, `healthy`) and a Signal Forms field tree (`endpointForm`) with no side effects outside the HTTP call; nginx handles all routing concerns |
| Discoverability | Backend OpenAPI spec and Swagger UI available at runtime |

## Fitness criteria

- `docker compose up` completes without error on a machine with only Docker installed
- `GET http://localhost:4200/` returns `200 OK` with `Content-Type: text/html`
- The page body contains `<app-root>`
- `GET http://localhost:4200/api/health` returns `200 OK` — nginx proxy to backend works
- `GET http://localhost:4200/api/health` response body has `{"status":"ok"}` — proxy forwards the payload correctly
- `GET http://localhost:8080/health` returns `{"status":"ok","version":"0.1.0"}` — backend reachable directly
- Opening `http://localhost:4200` in a real browser shows "Project is confirmed to be healthy" — rendered by Playwright
- `docker compose down` exits cleanly with no containers remaining

## Running the tests

```sh
bb test                # all tests
bb test:backend        # backend unit and integration tests (no Docker)
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

The nginx container proxies requests to the backend. A misconfigured proxy could expose internal routes not intended for public access.

### What design choices reduce the risk?

The proxy rule is explicit: only `/api/` is forwarded. All other paths are served from the static file root or fall through to `index.html`. In this development configuration the backend also exposes port 8080 directly to the host for inspection via Swagger UI; in production that port binding would be removed so the backend is only reachable through the frontend container's network.

### What is deliberately not solved yet?

TLS, authentication, CSRF protection, rate limiting, CORS policy.

## Production notes

- Replace the `http://backend:8080` proxy target with the actual backend service hostname or internal load balancer
- Add `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` when behind a real load balancer
- The Angular build output is immutable (content-hashed filenames); only `index.html` must not be cached

## What is not proven yet

- Authentication and session management
- HTTPS / TLS termination
- Multiple backend instances
- Real-time updates (SSE)

## What I learned

- Angular's esbuild builder detects `tailwind.config.js` automatically — no PostCSS config required
- nginx `proxy_pass http://backend:8080/` (trailing slash) strips the `/api/` prefix before forwarding — without it, the full `/api/health` path would be forwarded to the backend
- `docker compose up --wait` with `depends_on: condition: service_healthy` ensures the backend is ready before the frontend starts
- Angular 17+ `@if`/`@else` block syntax is built-in — no `CommonModule` import required
- Playwright's `getByText` with a 10-second timeout handles the async health check without a fixed sleep
- `localhost` resolves to `::1` (IPv6) in nginx:alpine because nginx `listen 80` binds only to IPv4 — healthchecks must use `127.0.0.1` explicitly
- `bb test` starts the Docker stack twice (once for `test:acceptance`, once for `test:e2e`) because each task manages its own lifecycle, making every task independently runnable; the duplication is intentional
- Removing zone.js requires two changes: `polyfills: []` in `angular.json` and `provideZonelessChangeDetection()` in `bootstrapApplication` — Angular's build does not warn if you do only one
- `toSignal(observable, { initialValue })` with `toObservable(signal)` + `switchMap` is the canonical zoneless pattern for HTTP calls that depend on reactive state: the signal drives re-requests, `startWith(null)` resets the loading state on each submission
- Signal Forms (`@angular/forms/signals`) wraps a `WritableSignal` in a `FieldTree`; `form(model)` returns the tree, and `[formField]` binds it to a native input — the form is the model, not a separate copy of it
- jest-preset-angular 16 requires Jest 30 and TypeScript ≥ 5.9; when upgrading from Angular 18 all three must be bumped together
- `npm --force` is needed to fix a lockfile that deduplicates a package to a version that satisfies one consumer but not another (`@noble/hashes@1.4.0` satisfying `pkijs` but not `@exodus/bytes@^1.8.0||^2.0.0`)
