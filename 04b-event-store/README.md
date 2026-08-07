# Event-sourced shopping cart

A small Clojure project demonstrating **optimistic concurrency control** for an
event store on Postgres.

Read [SPEC.md](SPEC.md) first — it says what this has to do and why.
Read [docs/TEST_STRATEGY.md](docs/TEST_STRATEGY.md) for the test layers and
local/CI gates.

## The one idea

Two people confirm the same cart at the same moment. Both read version 1. Only
one may write.

Every stream has a version — how many events it has. Reading gives you the
version. Writing says *"only accept this if it is still what I read."*

- A reads at version 1, writes "expect 1" → succeeds, stream becomes 2.
- B read at version 1, writes "expect 1" → it is 2 now → **rejected**.

Three things make that work, and everything in `src` is one of them:

1. **The database assigns the version.** Never `(count events)` — that is wrong
   for partial reads and snapshot folds, and it fails silently.
2. **Check and write are one statement.** `UPDATE ... WHERE stream_position = 1`.
   Zero rows updated means you lost.
3. **Losing is a value, not an exception.** `[:conflict {:expected 1 :current 2}]`.

## Quick start

```bash
bb install     # pinned tools via mise
bb test:core   # pure tests, ~1s, no Docker
bb test:perf   # HTTP performance smoke tests, no Docker
bb test        # everything, starts Postgres 18.4 in a container
bb up          # start Postgres, migrate, run the HTTP API
bb down        # stop Compose, remove the DB volume, clean artefacts
bb run:memory  # start the HTTP API locally without Postgres
```

`bb help` covers Rancher Desktop setup if adapter tests can't find Docker.

## HTTP API

The HTTP contract is design-first. The checked-in OpenAPI document at
`resources/openapi/cart-api.openapi.json` is the source of truth, and the
service serves it unchanged from `/openapi.json`.

The service is a driving adapter over command and query use-case ports. Commands
are task-based endpoints; clients do not post a generic command envelope. Queries
are POST endpoints, matching CQRS query handlers instead of resource-shaped GETs.
The HTTP layer parses JSON, validates task shape, stamps `:metadata {:now ...}`
when the caller omits metadata, and maps application results to HTTP responses.
It does not call `cart.core`, derive event-stream names, read the event store, or
fold events itself.

JSON over HTTP is UTF-8. Responses declare
`application/json; charset=utf-8`, request bodies are decoded as UTF-8, and the
Postgres event-store database is created with UTF8 encoding. The test suite
round-trips English, Chinese and Arabic text through HTTP, JSONB and Postgres
text columns. API errors remain stable machine-readable codes; localized
messages belong in the driving shell/UI, not the pure core.

Run it with the in-memory store:

```bash
bb run:memory
```

Run it with Postgres. This starts Compose Postgres, runs Flyway as a one-shot
container, then runs the API in the foreground as `cart_app`.

```bash
bb up
```

The lower-level tasks are still available when you need them: `bb db:up`,
`bb migrate`, and `bb run`.

Useful endpoints:

```bash
curl -sS http://localhost:8080/health
curl -sS http://localhost:8080/openapi.json

curl -sS -X POST http://localhost:8080/commands/add-product-item \
  -H 'content-type: application/json' \
  -d '{"cart-id":"c1","product-item":{"product-id":"sku-1","quantity":2,"unit-price":1299}}'

curl -sS -X POST http://localhost:8080/queries/get-cart \
  -H 'content-type: application/json' \
  -d '{"cart-id":"c1"}'

curl -sS -X POST http://localhost:8080/queries/get-cart-events \
  -H 'content-type: application/json' \
  -d '{"cart-id":"c1"}'
```

Pin an optimistic version with the optional body field `"expected-version": 0`.
The other accepted values are `"any"` and `"stream-does-not-exist"`. If omitted,
the application service reads the stream and derives the expected version it just
observed.

When you are done with the local Postgres container:

```bash
bb down
```

## Layout

```
SPEC.md                                requirements
docs/TEST_STRATEGY.md                  test layers, gates, CI policy
resources/db/migration/V1__*.sql       tables + the append_to_stream function
resources/openapi/cart-api.openapi.json contract-first HTTP API

src/cart/core.clj                      PURE. no requires at all.
src/cart/schema.clj                    malli schemas. describes core from outside.
src/cart/port/event_store.clj          the protocol
src/cart/port/cart_command.clj         command-side protocol
src/cart/port/cart_query.clj           query-side protocol
src/cart/app/command.clj               cart command use cases
src/cart/app/handle.clj                read -> fold -> decide -> append
src/cart/app/query.clj                 CQRS query handlers
src/cart/app/stream.clj                stream id naming
src/cart/adapter/driven/
    event_store_postgres.clj           real store
    event_store_memory.clj             fast store for tests
src/cart/adapter/driving/http.clj      Ring/Reitit JSON API
src/cart/system.clj                    component system: store + Jetty
src/cart/main.clj                      env config + process entrypoint
compose.yaml                           local Postgres + one-shot Flyway service

test/cart/core_test.clj                pure, no fixtures
test/cart/serialisation_test.clj       generative JSONB storage round-trip
test/cart/app/command_test.clj         command use-case boundary tests
test/cart/app/query_test.clj           CQRS query handler tests
test/cart/adapter/driven/
    event_store_contract.clj           shared behaviours both stores must satisfy
    append_fn_test.clj                 races at the SQL function level
    event_store_postgres_test.clj      races through the EventStore protocol
test/cart/adapter/driving/
    http_test.clj                      HTTP contract, statuses, validation
    system_test.clj                    component lifecycle smoke test
test/cart/acceptance/
    http_postgres_test.clj             real HTTP over Jetty plus Testcontainers Postgres
```

`cart.core` requires nothing — not even `cart.schema`. Schemas are malli
vocabulary, and depending on them would couple the core to malli whether or not
a `require` form is present.

## The tests that matter

Races are tested at two levels, so a failure localises immediately — SQL
function (`append_fn_test.clj`) or Clojure marshalling
(`event_store_postgres_test.clj`).

The Postgres adapter tests use Testcontainers with the same lifecycle shape as
local Compose: a Postgres 18.4 container starts first, then a one-shot
`flyway/flyway` container runs the migration against it before any store tests
run. The app role test then connects as `cart_app` to prove runtime privileges
are sufficient without DDL/table-write grants.

Both fire two appends at the same expected version
from two threads released together by a `CyclicBarrier`, on separate
connections, twenty times over. It asserts:

1. exactly one returns `[:ok ...]`
2. exactly one returns `[:conflict ...]`
3. neither throws
4. the loser is told the version it actually is now
5. **only the winner's events exist in the table**

Point 5 is the one that counts. A test checking only return values would pass
even if the loser had written rows anyway.

Point 3 needs care. Results go into three buckets — won, lost, **threw** — never
two. `(remove :success results)` counts a crashed thread as a legitimate loser,
so a test written that way passes whether the loser returned cleanly or died on
a unique-constraint violation. That is exactly the difference the SQL function
exists to make.

## Where the SQL lives

All concurrency control is in `append_to_stream` in the migration. The Clojure
adapter just marshals arguments. Doing the branch in Clojure would mean three
round trips with the decision made outside the transaction that enforces it.

The service does not apply migrations on startup. For local containerized
development, `bb migrate` runs `flyway/flyway` as a one-shot service against the
Compose Postgres database. The app connects as `cart_app`, which can read event
streams and execute `append_to_stream`, but does not need DDL privileges.

Event payloads and metadata are plain `jsonb`, passed with typed Postgres
`PGobject` values. That keeps rows queryable with native operators such as
`message_data ->> 'cart-id'`. Event type is separate in `message_type`, so it is
reconstructed as a keyword on read without requiring a Clojure-specific encoding.
The `event_store` database is created with UTF8 encoding by the Postgres init
script used by Compose and Testcontainers.
The DDL also rejects impossible state directly: orphan messages, duplicate event
ids, non-positive positions and non-object JSON payloads/metadata.

## Still not included

Deliberately out of scope: projections and read models, snapshots, multi-tenancy,
archiving. `messages` carries `global_position` and
`transaction_id` columns for the first of those, unused for now, because adding
columns to an append-only table later is painful.
