# 01-hello-backend

## What this demonstrates

A minimal Clojure Ring/Reitit backend serving a single health endpoint, runnable with `docker compose up`. This is the base shape that all subsequent backend projects extend.

## Architecture

One Docker service: a JVM uberjar built with `tools.build` and run on a minimal Alpine JRE image as a non-root user.

| Role | Choice | Rationale |
|---|---|---|
| HTTP contract | Ring | Handlers are plain `request → response` functions — the stable abstraction |
| Routing | Reitit | Routes are data, not macros — easy to test in isolation |
| Content negotiation | Muuntaja | JSON encoding handled by middleware, not by handler code |
| HTTP server | Jetty | Embedded, no external server process |
| API contract | Reitit OpenAPI 3.0 | Spec generated from route annotations — no separate spec file to maintain |
| API browser | ring-swagger-ui | Swagger UI served at `/swagger-ui` pointing at the generated spec |

## How to run it

### 1. Install mise

[mise](https://mise.jdx.dev) manages the tool versions declared in `mise.toml`. Install it once on your machine:

```sh
curl https://mise.run | sh
```

### 2. Install declared tools (bootstrap only)

Run once to install babashka and the other declared tools. This is the only manual step — you need `bb` on your `PATH` before you can use the tasks below.

```sh
mise install  # install Java, Clojure, Babashka, clj-kondo, cljfmt
```

### 3. Run everything

```sh
bb all   # trust config + install tools + build image + run all tests
```

`bb all` calls `mise trust` and `mise install` internally, so after the first `mise install` you only ever need `bb all`.

### 4. Start and stop the stack manually

```sh
bb up    # build and start the stack
bb down  # stop the stack
```

Without Babashka:

```sh
docker compose up
docker compose down
```

## What to look at

- [backend/src/es_lab/hello_backend/routes.clj](backend/src/es_lab/hello_backend/routes.clj) — route data, handler, and OpenAPI annotations
- [backend/src/es_lab/hello_backend/core.clj](backend/src/es_lab/hello_backend/core.clj) — entry point
- [docker-compose.yml](docker-compose.yml) — healthcheck configuration
- [acceptance/health_test.clj](acceptance/health_test.clj) — acceptance tests proving the stories in SPEC.md

## Quality attributes demonstrated

| Attribute | How |
|---|---|
| Portability | Runs with `docker compose up` — no local language runtimes required |
| Operability | Health endpoint follows the pattern used by load balancers and container orchestrators |
| Maintainability | Routes are data; handler is a pure function; one feature is understandable end to end |
| Discoverability | OpenAPI spec and Swagger UI generated from route annotations — contract stays in sync with code |

## Endpoints

After `bb up` the stack is available at:

| URL | Description |
|---|---|
| `http://localhost:8080/health` | Returns `{"status":"ok","version":"0.1.0"}` with `Content-Type: application/json` |
| `http://localhost:8080/openapi.json` | OpenAPI 3.0 spec generated from route annotations |
| `http://localhost:8080/swagger-ui` | Interactive Swagger UI browser |

## Fitness criteria

- `docker compose up` completes without error on a machine with only Docker installed
- `GET http://localhost:8080/health` returns `200 OK`
- Response body is `{"status":"ok","version":"0.1.0"}`
- `Content-Type` header is `application/json`
- Docker Compose healthcheck reaches `healthy` within 30 seconds
- `docker compose down` exits cleanly with no containers remaining
- `GET http://localhost:8080/openapi.json` returns a valid OpenAPI 3.0 document renderable by Swagger UI
- `GET http://localhost:8080/swagger-ui` serves the Swagger UI browser

## Running the tests

```sh
bb test              # all tests
bb test:unit         # unit and integration tests (no Docker required)
bb test:acceptance   # acceptance tests against the live stack
```

## Code quality

```sh
bb lint              # lint with clj-kondo
bb fmt:check         # check formatting with cljfmt
bb fmt:fix           # reformat in place with cljfmt
```

## Security design notes

### What could go wrong?

The container process could run as root, giving an attacker full root access inside the container if the application is compromised.

### What design choices reduce the risk?

The container runs as a non-root user (`app`), created explicitly in the Dockerfile.

### What is deliberately not solved yet?

Authentication, TLS, rate limiting, secrets management, audit logging. None are applicable to a single health endpoint serving no user data.

### How would this be hardened in production?

Add a read-only root filesystem, drop all Linux capabilities, add resource limits, and route through an API gateway with TLS termination.

## Configuration and evolution notes

### What is hardcoded in this project?

The version string (`"0.1.0"`) and the port (`8080`).

### What has become configurable?

Nothing yet.

### What must remain protected by code?

N/A — no domain logic, no user data, no business rules.

### How is configuration versioned?

N/A — no configuration yet.

### How are historical submissions, workflows, or decisions preserved?

N/A — no state.

## Production notes

- The JVM needs memory tuning (`-Xmx`, `-Xms`) for production
- The port is configurable via the `APP_PORT` environment variable (defaults to `8080`)
- The version should be injected at build time from `git describe` or a CI variable
- Structured JSON logging is not implemented

## What is not proven yet

- Authentication
- Persistence
- TLS
- Structured logging
- Horizontal scaling
- Health beyond "the process started" — no dependency checks

## What I learned

- Ring handlers are plain functions; middleware is the extension point; routing is data
- Docker multi-stage builds keep the runtime image small and dependency-free
- `docker compose up --wait` blocks until healthchecks pass — the right primitive for acceptance tests
