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
cart.app.handle            orchestration: read -> fold -> decide -> append.
cart.adapter.driven.*      postgres and in-memory event stores.
```

### R1.1 — the core has no dependencies

`cart.core` MUST NOT require any namespace, including `cart.schema`. Schemas are
malli vocabulary; depending on them would couple the core to malli regardless of
whether a `require` form is present.

### R1.2 — dependency arrows point inward

`cart.schema` MUST NOT require `cart.core`. Shared keywords are written as
fully-qualified literals in both places (e.g. `:cart.event/confirmed`), because
a keyword is a value and costs no dependency.

`cart.app.handle` depends on `cart.port.event-store`, never on a concrete
adapter.

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
| `stream_id`       | TEXT   | primary key              |
| `stream_type`     | TEXT   | e.g. `shopping_cart`     |
| `stream_position` | BIGINT | the version              |

This table exists so that concurrent writers have a single row to collide on.

### `messages` — one row per event

| column             | type        | notes                              |
|--------------------|-------------|------------------------------------|
| `stream_id`        | TEXT        |                                    |
| `stream_position`  | BIGINT      | 1-based, per stream                |
| `message_id`       | TEXT        | uuid                               |
| `message_type`     | TEXT        | e.g. `cart.event/confirmed`        |
| `message_data`     | JSONB       | transit-encoded                    |
| `message_metadata` | JSONB       |                                    |
| `global_position`  | BIGINT      | from a sequence; unused for now    |
| `transaction_id`   | XID8        | `pg_current_xact_id()`; unused now |
| `created`          | TIMESTAMPTZ |                                    |

`PRIMARY KEY (stream_id, stream_position)`.

### R3.1 — version 0 means the stream does not exist

Real events start at position 1.

### R3.2 — the primary key is a backstop

The PK on `messages` MUST exist so that a hole in the version logic surfaces as
a database error rather than a silent double-write.

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
`max(length)` rows. Mismatched input arrays would write more events than the
version reserved. The function MUST reject arrays of differing length rather
than trusting its only caller.

## 5. Serialisation

### R5.1 — transit, not plain JSON

Event payloads are transit-encoded into a `jsonb` column. Plain JSON silently
turns `:cart.event/confirmed` into the string `"cart.event/confirmed"`, which
matches no `evolve` method and is dropped by the `:default` case — wrong state,
no error.

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

### R6.3 — events survive a round trip

A generative test encodes and decodes malli-generated events and asserts
equality, catching keyword, instant and numeric drift.

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

### R6.6 — races are tested at two levels

| level    | file                       | asserts on                          |
|----------|----------------------------|-------------------------------------|
| SQL      | `append_fn_test.clj`       | `:success`, `:next_position`, `:current_position` |
| protocol | `event_store_postgres_test.clj` | `[:ok ...]` / `[:conflict ...]` |

A failure at only one level localises the fault immediately: SQL function, or
Clojure marshalling.

### R6.7 — a crashed thread must not be counted as a clean loser

Race results MUST be sorted into three buckets — won, lost, threw — never two.

`(remove :success results)` counts a thrown exception as a legitimate loss,
because a non-map fails the `:success` lookup. A test written that way passes
whether the loser returned `success = false` or died with a unique-constraint
violation — which is precisely the distinction R4.3 exists to enforce.

Every race test MUST assert the `threw` bucket is empty.

---

## 7. Environment

- Postgres **18.4**, started per test run by Testcontainers.
- Schema applied by Flyway from `resources/db/migration`.
- Tooling pinned by `mise.toml`; tasks in `bb.edn`.
- Docker must be running for adapter tests. Core tests do not need it.
