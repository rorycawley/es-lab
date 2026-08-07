# SPEC

A minimal event-sourced shopping cart, built to demonstrate **optimistic
concurrency control** in Postgres.

The point of this project is one guarantee: *when two requests act on the same
cart at the same moment, exactly one of them may write.*

---

## 1. Architecture

Functional core, imperative shell, arranged as ports and adapters.

```
cart.core                  pure. clojure.core only. no requires at all.
cart.schema                malli schemas. describes core's data from outside.
cart.port.event-store      protocol the app depends on.
cart.port.cart-command     command-side protocol driving adapters depend on.
cart.port.cart-query       query-side protocol driving adapters depend on.
cart.app.command           cart command use cases.
cart.app.handle            stream-level command service: read -> fold -> decide -> append.
cart.app.query             query use cases.
cart.app.stream            stream id naming.
cart.adapter.driven.*      postgres and in-memory event stores.
cart.adapter.driving.http  Ring/Reitit JSON API.
cart.system                component lifecycle wiring.
cart.main                  process entrypoint.
resources/openapi/*        contract-first HTTP API documents.
compose.yaml               local Postgres + one-shot Flyway service.
```

### R1.1 — the core has no dependencies

`cart.core` MUST NOT require any namespace, including `cart.schema`. Schemas are
malli vocabulary; depending on them would couple the core to malli regardless of
whether a `require` form is present.

### R1.2 — dependency arrows point inward

`cart.schema` MUST NOT require `cart.core`. Shared keywords are written as
fully-qualified literals in both places (e.g. `:cart.event/confirmed`), because
a keyword is a value and costs no dependency.

Application use cases depend on ports, never on concrete adapters.

Driving adapters MUST NOT require `cart.core` directly. Commands enter through
`cart.port.cart-command` and command use cases. Queries enter through
`cart.port.cart-query` and query handlers.

### R1.3 — no side effects in the core

`decide`, `evolve`, `initial-state` and `fold` MUST be pure. Business rule
violations are returned as data (`[:error {:reason ...}]`), never thrown.
Timestamps arrive on the command's `:metadata`, never read from a clock.

---

## 2. Domain

A shopping cart. Three states:

| status    | meaning                                   |
|-----------|-------------------------------------------|
| `:empty`  | initial state, nothing has happened       |
| `:opened` | has at least one product item             |
| `:closed` | confirmed or cancelled; no further writes |

### Commands

| type                              | data                       |
|-----------------------------------|----------------------------|
| `:cart.command/add-product-item`  | cart-id, product-item      |
| `:cart.command/remove-product-item` | cart-id, product-item    |
| `:cart.command/confirm`           | cart-id                    |
| `:cart.command/cancel`            | cart-id                    |

All commands carry `:metadata {:now <instant>}`.

### Events

| type                                | data                                |
|-------------------------------------|-------------------------------------|
| `:cart.event/product-item-added`    | cart-id, product-item, added-at     |
| `:cart.event/product-item-removed`  | cart-id, product-item, removed-at   |
| `:cart.event/confirmed`             | cart-id, confirmed-at               |
| `:cart.event/cancelled`             | cart-id, cancelled-at               |

### Business rules

- R2.1 — adding or removing on a `:closed` cart is an error (`:cart-closed`).
- R2.2 — removing more of a product than the cart holds is an error
  (`:insufficient-quantity`).
- R2.3 — confirming a cart that is not `:opened` is an error (`:not-opened`).
- R2.4 — cancelling a `:closed` cart is an error (`:already-closed`).
- R2.5 — money is stored as integer minor units, never a decimal.

### R2.6 — `evolve` must tolerate unknown events

`evolve` MUST have a `:default` method returning state unchanged. An event type
written by a newer deployment and read back after a rollback must not make the
aggregate unloadable.

`decide` MUST NOT have a `:default` method. An unknown command is a bug in
current code and should fail loudly.

---

## 3. Storage

Two tables.

### `streams` — one row per cart

| column            | type   | notes                    |
|-------------------|--------|--------------------------|
| `stream_id`       | TEXT   | primary key, non-empty   |
| `stream_type`     | TEXT   | e.g. `shopping_cart`, non-empty |
| `stream_position` | BIGINT | the version, positive    |

This table exists so that concurrent writers have a single row to collide on.

### `messages` — one row per event

| column             | type        | notes                              |
|--------------------|-------------|------------------------------------|
| `stream_id`        | TEXT        |                                    |
| `stream_position`  | BIGINT      | 1-based, per stream                |
| `message_id`       | UUID        | unique event id                    |
| `message_type`     | TEXT        | e.g. `cart.event/confirmed`        |
| `message_data`     | JSONB       | plain JSON event payload           |
| `message_metadata` | JSONB       | plain JSON request provenance      |
| `global_position`  | BIGINT      | from a sequence; unused for now    |
| `transaction_id`   | XID8        | `pg_current_xact_id()`; unused now |
| `created`          | TIMESTAMPTZ |                                    |

`PRIMARY KEY (stream_id, stream_position)`.

`messages.stream_id` has a foreign key to `streams.stream_id`. `message_id` is
globally unique. `message_data` and `message_metadata` must be JSON objects; the
store does not accept arbitrary scalar/array JSON as an event payload.

### R3.1 — version 0 means the stream does not exist

Real events start at position 1.

### R3.2 — the primary key is a backstop

The PK on `messages` MUST exist so that a hole in the version logic surfaces as
a database error rather than a silent double-write.

The DDL MUST also reject impossible state if the SQL function is bypassed:
non-positive positions, orphan messages, duplicate message ids, empty message
types and non-object JSON payloads/metadata.

### R3.3 — global_position and transaction_id are provisioned now, used later

`messages` is append-only; adding columns to it later is expensive. Both columns
fill themselves via `DEFAULT` and are ignored by application code until
background projections exist.

---

## 4. Concurrency

### R4.1 — the database assigns the version

Application code MUST NOT compute a stream's version by counting events. A count
is wrong whenever the stream was read partially or folded from a snapshot, and
the failure is a silent lost update.

`read-stream` returns the version alongside the events. It is derived from the
last event's `stream_position` in a **single query**, so events and version are
always from the same snapshot.

### R4.2 — check and write are one atomic statement

The version check MUST happen inside the same statement as the write. A
read-then-write from the client is a race no matter how carefully it is coded.

### R4.3 — losing means zero rows

| case                           | statement                              | loss signal    |
|--------------------------------|----------------------------------------|----------------|
| stream is new (expected 0)     | `INSERT ... ON CONFLICT DO NOTHING`    | 0 rows inserted |
| stream exists (expected N)     | `UPDATE ... WHERE stream_position = N` | 0 rows updated  |

Both paths MUST report failure the same way. Neither may abort the transaction,
because a losing write must not destroy other legitimate work in flight.

### R4.4 — expected version has three modes

| caller passes           | meaning                                    |
|-------------------------|--------------------------------------------|
| a number `N`            | "I read version N; fail if it has changed" |
| `:stream-does-not-exist`| "create this; fail if it already exists"   |
| `:any`                  | "just append; I am not checking"           |

`:stream-does-not-exist` MUST be genuinely enforced.

`:any` MUST NOT be able to conflict. Reading the version and then updating
`WHERE stream_position = <that value>` is not "no check" — a writer committing
in the gap moves the version and the update matches nothing, producing a
conflict the caller explicitly opted out of. `:any` uses a real upsert
(`ON CONFLICT ... DO UPDATE SET stream_position = streams.stream_position + n`),
which blocks on the contended row and then applies on top of whatever
committed.

### R4.5 — the loser learns the real current version

On conflict the store returns `[:conflict {:expected ... :current ...}]` where
`:current` is the **freshest committed version at the moment the conflict was
detected**. This requires re-reading `streams` after the conflict is detected;
the value read at the start of the call is stale by then.

With two writers that is exactly "the version after the winner committed". With
three or more it may be *past* the immediate winner — a third writer can commit
between the loser losing and the loser re-reading. That is deliberate: the
point of `:current` is to tell the loser what it would have to rebase onto, and
the freshest value is the more useful answer. Callers must therefore treat
`:current` as a lower bound on the truth, not a value to compute the next
expected version from arithmetically. The only safe response to a conflict is to
re-read the stream, which is what R4.8's retry does.

### R4.6 — conflicts are data, not exceptions

`append-to-stream` returns `[:ok {...}]` or `[:conflict {...}]`. The shell
decides whether that becomes a retry or an HTTP 412.

### R4.7 — an empty decision writes nothing

When `decide` returns no events, `handle-command` MUST NOT call the store. No
write, no version bump, no possibility of conflict.

### R4.8 — retry is opt-in

Retry re-runs the whole read → fold → decide → append cycle against freshly read
state, never just the append. This is only safe because `decide` is pure.
Disabled unless configured. Default when enabled: 3 retries, 100ms base,
factor 1.5.

---

### R4.9 — read committed isolation is required

The design rests on a losing `UPDATE` matching zero rows. That is a read
committed behaviour: Postgres waits for the blocking transaction, then
re-evaluates the `WHERE` against the updated row.

At **repeatable read or serializable** Postgres does not re-evaluate. It raises
`40001 could not serialize access due to concurrent update` — an exception,
which aborts the transaction and makes R4.3 false.

`append_to_stream` MUST therefore assert its isolation level and raise if it is
not read committed, so that the constraint fails loudly rather than silently.

### R4.10 — the function validates its own inputs

`unnest` of several arrays pads the shorter ones with NULL and yields
`max(length)` rows. It also flattens multidimensional arrays. Either behaviour
can write a different number of events than the version reserved. The function
MUST reject arrays that are not one-dimensional, differ in length, contain NULL
elements, contain duplicate message ids, contain empty message types, or carry
non-object JSON payloads/metadata. These checks happen before the stream version
is claimed.

## 5. Serialisation

### R5.1 — plain JSONB, not an opaque Clojure encoding

Event payloads and metadata are stored as plain JSON in `jsonb` columns, using
typed Postgres `jsonb` parameters from the adapter. This keeps stored events
inspectable and queryable with native JSONB operators, for example
`message_data ->> 'cart-id'`.

The event type is not stored inside JSON. It lives in `message_type` as text and
is reconstructed as a Clojure keyword on read. JSON fields themselves must be
JSON-compatible data: maps, vectors, strings, numbers, booleans and null. Keyword
values inside `message_data` or `message_metadata` are encoded as JSON strings.

HTTP request bodies MUST be decoded as UTF-8, and JSON responses MUST declare
`application/json; charset=utf-8`. The Postgres database used by the event store
MUST be created with UTF8 encoding. English, Chinese and Arabic user data must
round-trip through HTTP JSON, `text` columns and `jsonb` without lossy
conversion.

Domain events, commands and errors carry stable machine-readable values, not
localized display text. Localized English, Chinese or Arabic labels/messages
belong in the driving shell or UI layer, selected by an explicit locale policy,
not in `cart.core`.

### R5.2 — events are validated on the way out of storage

Events read from Postgres MUST be validated against `cart.schema/Event`. A
failure throws: an uninterpretable event means state would be computed from
incomplete history.

Commands built in-process are NOT re-validated at runtime.

### R5.3 — event schemas are open, command and state schemas are closed

| data     | `{:closed true}`? | why                                        |
|----------|-------------------|--------------------------------------------|
| commands | yes               | built in-process by current code           |
| state    | yes               | in-memory only, current code               |
| events   | **no**            | read back by past and future deployments   |

### R5.4 — event schemas may only be relaxed

Adding an optional field or relaxing a constraint is safe. Adding a required
field, tightening a constraint, or renaming a field is not — introduce a new
event type and keep an `evolve` method for the old one indefinitely.

---

## 6. Tests

### R6.1 — the core is tested without infrastructure

`cart.core` tests use no fixtures, no Docker, no I/O.

### R6.2 — every command has a `decide` method

A test compares the dispatch values in `cart.schema/Command` against
`(methods decide)`. This substitutes for TypeScript's exhaustiveness check.

### R6.3 — events survive the storage round trip

A generative test encodes malli-generated events into their storage shape
(`message_type`, `message_data`, `message_metadata`), decodes them, and asserts
equality. This catches drift in event type reconstruction, JSON keys and numeric
values. A separate test fixes the explicit contract that keyword values inside
JSON become strings.

### R6.4 — concurrency is proven against real Postgres

Two appends at the same expected version, released simultaneously by a
`CyclicBarrier`, on **separate connections**. Assertions:

1. exactly one returns `[:ok ...]`
2. exactly one returns `[:conflict ...]`
3. neither throws
4. the loser's `:current` is the post-winner version
5. **only the winner's events exist in `messages`**

Assertion 5 is the one that matters: a test checking only return values would
pass even if the loser had written rows anyway.

The same test exists for two concurrent *creates*, which take the INSERT path
rather than the UPDATE path.

Each race is repeated 20 times, because a race that happens not to interleave
proves nothing.

### R6.5 — the same suite runs against both adapters

The in-memory and Postgres stores are exercised by one shared set of contract
tests, so the fast store cannot drift from the real one.

### R6.6 — Postgres tests run migrations through a Flyway Testcontainer

The Postgres adapter fixture MUST start a Postgres Testcontainer and then run a
separate one-shot Flyway Testcontainer against it before tests receive a
datasource. Tests MUST NOT call Flyway in-process as a shortcut.

The Testcontainers fixture should mirror local Compose: same Postgres major
version, same initdb role script, same migration directory, same Flyway image,
and the app role `cart_app` tested through the real EventStore port.

### R6.7 — races are tested at two levels

| level    | file                       | asserts on                          |
|----------|----------------------------|-------------------------------------|
| SQL      | `append_fn_test.clj`       | `:success`, `:next_position`, `:current_position` |
| protocol | `event_store_postgres_test.clj` | `[:ok ...]` / `[:conflict ...]` |

A failure at only one level localises the fault immediately: SQL function, or
Clojure marshalling.

### R6.8 — a crashed thread must not be counted as a clean loser

Race results MUST be sorted into three buckets — won, lost, threw — never two.

`(remove :success results)` counts a thrown exception as a legitimate loss,
because a non-map fails the `:success` lookup. A test written that way passes
whether the loser returned `success = false` or died with a unique-constraint
violation — which is precisely the distinction R4.3 exists to enforce.

Every race test MUST assert the `threw` bucket is empty.

---

## 7. HTTP API

The HTTP API is contract-first. The checked-in OpenAPI document under
`resources/openapi` is the source of truth. The service MUST serve that contract
unchanged from `/openapi.json`; it MUST NOT generate the public contract from
routes.

The HTTP API is a driving adapter. It MUST NOT contain cart business rules,
derive event-stream names, read from the event store, fold events, or call the
domain core. Writes MUST go through `cart.port.cart-command`.

This application is CQRS. HTTP reads MUST go through `cart.port.cart-query` and
query handlers. HTTP MUST NOT require `cart.core`, `cart.app.*`, or
`cart.port.event-store` directly.

### R7.1 — commands are task-based endpoints

Command endpoints are explicit tasks:

| endpoint                       | command type                         |
|--------------------------------|--------------------------------------|
| `POST /commands/add-product-item`    | `:cart.command/add-product-item`    |
| `POST /commands/remove-product-item` | `:cart.command/remove-product-item` |
| `POST /commands/confirm-cart`        | `:cart.command/confirm`             |
| `POST /commands/cancel-cart`         | `:cart.command/cancel`              |

Clients MUST NOT post a generic command envelope to a catch-all command-bus
route. The adapter maps each task endpoint to the internal command type, then
validates the resulting command against `cart.schema/Command` before calling the
command use-case port.

### R7.2 — HTTP owns JSON, validation and metadata defaults

The adapter parses JSON request bodies with keyword keys. Task bodies carry
`cart-id` directly. Product item tasks also carry `product-item`.

If command metadata is omitted, the HTTP adapter stamps `{:now <epoch millis>}`.
The core still never reads a clock.

### R7.3 — expected version is explicit at the HTTP boundary

`expected-version` is an optional command body field. Accepted values:

| value                   | command-port expected version |
|-------------------------|------------------------------|
| omitted                 | derive from the fresh read    |
| `0`, `1`, ...           | pinned numeric version        |
| `stream-does-not-exist` | create-only                   |
| `any`                   | append without checking       |

Invalid values return `400`.

### R7.4 — status code mapping is stable

| application result                         | HTTP status |
|--------------------------------------------|-------------|
| `[:ok ...]` creating a stream              | `201`       |
| `[:ok ...]` appending existing stream      | `200`       |
| `[:error {:reason ...}]`                   | `422`       |
| `[:conflict {:expected ... :current ...}]` | `409`       |

Malformed JSON, invalid command shape, invalid query shape and out-of-contract
fields return `400`.

### R7.5 — query responses come from the query stack

Queries are POST endpoints with request bodies, not resource-shaped GETs:

| endpoint                         | query handler  |
|----------------------------------|----------------|
| `POST /queries/get-cart`         | `cart-summary` |
| `POST /queries/get-cart-events`  | `cart-events`  |

The current query implementation may rebuild state from events internally, but
that is a query handler concern, not an HTTP concern.

### R7.6 — Component owns service lifecycle, not schema lifecycle

The service system uses `com.stuartsierra/component` to own stateful resources:
Postgres datasource, command event store, command handler, query handler, and
Jetty server.

Starting the HTTP service MUST NOT run Flyway migrations. Schema migration is a
separate deployment step, preferably a one-shot Flyway container or Kubernetes
Job/init container before the app starts. The app database role MUST NOT require
DDL privileges to serve traffic.

Stopping the system MUST stop Jetty and close the datasource.

### R7.7 — local Compose runs Flyway as a one-shot container

`compose.yaml` provides a local Postgres service and a `flyway/flyway` service.
The Flyway service mounts `resources/db/migration` read-only and exits after
`migrate`.

The local app role is `cart_app`. It may read streams and execute
`append_to_stream`; it must not need table-write or DDL privileges.

### R7.8 — HTTP behaviour is tested at the adapter boundary

Handler tests MUST cover the served OpenAPI contract, task command success,
POST query reads, business rejection, optimistic conflict, `:any`, malformed
JSON, invalid expected version, invalid command shape, invalid query shape and
removal of legacy resource-shaped routes.

---

## 8. Environment

- Postgres **18.4**, started per test run by Testcontainers.
- Schema applied by Flyway from `resources/db/migration`.
- Local development may use Compose: `postgres` stays up, `flyway` is a
  one-shot migration container, and `cart_app` is the app role.
- Tooling pinned by `mise.toml`; tasks in `bb.edn`.
- Docker must be running for adapter tests. Core tests do not need it.
