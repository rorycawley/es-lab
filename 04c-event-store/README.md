# Shopping Cart Backend

This directory realizes [SPEC2.md](SPEC2.md) as the Clojure backend described in
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Iteration 0 provides the
walking skeleton, fixed architecture decisions, API contract, traceability and
the shared command outcome pipeline. Cart behavior begins in Iteration 1.

## Prerequisites

- Java 21
- Clojure CLI 1.12
- Babashka
- mise for pinned lint and formatting tools
- Docker for local PostgreSQL migration work

```sh
bb install
bb precommit
bb ci                 # includes disposable PostgreSQL and Flyway containers
bb run:memory
```

The test taxonomy, suite ownership, isolation rules and iteration-by-iteration
evolution are defined in [docs/TEST_STRATEGY.md](docs/TEST_STRATEGY.md).
Iteration 0 provides these focused entry points:

```sh
bb test:policy
bb test:http-contract
bb test:component
bb test:migrations
bb test:migrations-postgres
bb test:traceability
bb test:architecture
```

The 91 `SPEC2.md` cases remain Planned until their business slices are
implemented; the Iteration 0 traceability test proves complete scheduling, not
business acceptance.

The local server binds to `127.0.0.1:8080` and exposes:

- `GET /health`
- `GET /ready`
- `GET /openapi.json`

Business endpoints are defined in OpenAPI but are not routed until their slices
are delivered. This avoids exposing placeholder behavior that could be mistaken
for an implemented use case.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `CART_STORE` | `memory` | `memory`, `sqlite` or `postgres` |
| `BIND_HOST` | `127.0.0.1` | HTTP bind address |
| `PORT` | `8080` | HTTP port; use `0` in tests |
| `TRUSTED_UPSTREAM_ENFORCED` | `false` | Required for wildcard binding |
| `JDBC_URL` / `DATABASE_URL` | none | Required for PostgreSQL configuration |
| `SQLITE_JDBC_URL` | `jdbc:sqlite:target/cart-event-store.sqlite3` | SQLite database |

Run migrations separately from service startup:

```sh
bb migrate:sqlite
bb db:up
bb migrate
```

The backend is not an authorization boundary. A deployed instance must be
reachable only through trusted upstream authentication and authorization.
