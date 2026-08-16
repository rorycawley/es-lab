# Clojure Backend Implementation Plan

This plan realizes `SPEC2.md` as a single deployable Clojure backend. It uses a
modular monolith, feature-first vertical slices, CQRS, domain events, event
sourcing, ports and adapters, task-based JSON endpoints, and a functional core
inside an imperative shell.

## 1. Requirements Review

The specification contains five use cases, 24 prepared slices, 91 black-box
acceptance tests, and 22 system-wide requirements. All 24 slices are Must.

This plan realizes the finalized `SPEC2.md`, including its fixed outcome
evaluation order, distinction between logical commands and delivery attempts,
semantic command equality, global request-ID race behavior, trusted deployment
boundary and system-wide requirement traceability. Section 8 assigns all test
cases to iterations.

The following decisions are fixed before the HTTP contract is frozen:

| Topic | Decision |
|---|---|
| Cart ownership | A cart owns product UUIDs and quantities only. It accepts and stores no price data. |
| Identifiers | Cart, product and command request identifiers are UUIDs. Command request UUIDs occupy one global namespace. Any supplied cart identifier that finds no cart has one `invalid-cart` outcome; format and existence are not separate public errors. |
| Product references | A valid product UUID is accepted as an opaque reference without a catalogue lookup. There is no product-catalogue port or adapter in this delivery. |
| Command idempotency | Every logical command requires a request UUID. Only an accepted command stores the UUID and business result. Semantically equal deliveries return that result, excluding delivery-specific metadata, even after later cart changes. Identical concurrent deliveries accept one logical command and one change. Different otherwise valid inputs racing on one unestablished UUID serialize so exactly one command succeeds and every loser is invalid. Invalid, rejected and conflicting attempts do not consume their UUIDs. |
| Idempotency retention | Accepted command results are retained indefinitely with the other cart data and have no independent expiry. |
| Quantity outcome | A requested quantity outside the integer range 1 through 1000 is invalid input (`400`). An otherwise valid addition that would raise the held quantity above 1000 is a business rejection (`422`). Use checked arithmetic. |
| Cart item representation | JSON cart views contain an `items` array of `{product-id, quantity}` objects sorted by the string form of product UUID in ascending order. |
| Closed contents | Confirmation and cancellation retain final product UUIDs and quantities while exposing only public status `closed`. |
| History shape | Revisions start at 1. Stable types are `product-item-added`, `product-item-removed`, `cart-confirmed` and `cart-cancelled`. Add/remove data contains product UUID and delta quantity; closure data is empty. No stream identifier or persistence metadata is exposed. |
| Acceptance time | Generate `accepted-at` as part of successful atomic acceptance and serialize it as a UTC RFC 3339 timestamp. |
| Query model | Every business query reads a projection. Query handlers never fold the event stream. |
| Query consistency | Event append and projection changes commit atomically, so queries have read-after-write consistency when a command returns success. |
| Persistent stores | PostgreSQL, SQLite and in-memory adapters must satisfy the same event-store, idempotency and projection contracts. |
| JSON contract | Reject every unknown request field, including nested unknown fields and all price fields. |
| Command result | Every successful command returns the complete cart view and observation produced by that command. An idempotent replay returns the stored original result, which may now be stale. |
| Error precedence | Implement `SWR-022` literally, and let the first failing step alone decide the response: validate request shape and every input value including observation-token authenticity; then resolve request-ID replay or misuse; then detect staleness; then evaluate business rules. A stale observation of a cart that has since been closed is therefore `409 conflict`, not `422 rejected`, and an invalid request carrying a stale observation is `400 invalid`. Write this ordering as one shared pipeline used by all four command handlers rather than per-slice conditionals, so no slice can reorder it. |
| Outcome categories | Follow the `SWR-008` split. Invalid input (`400`) means malformed or out-of-declared-range, independent of cart state: non-UUID or missing identifier, quantity outside 1 through 1000, unknown field, unauthentic observation token, cart identifier that finds no cart, or a missing observation where one is required. Business rejection (`422`) means well formed but forbidden by cart state: cart closed, removal below zero, confirmation of an empty cart, or an addition pushing a held quantity above 1000. |
| Observation integrity | Use versioned HMAC-signed opaque tokens bound to the cart. Forged, altered and wrong-cart tokens are invalid; authentic old tokens are conflicts. |
| Observation lifetime | Observations have no time-based expiry. They remain current until an accepted command changes the cart; they do not lock or reserve it. |
| Observation equality | Different authentic marker strings may represent the same cart revision, including across signing-key rotation. Compare represented cart and revision, never marker bytes. |
| History delivery | Change history is required release scope and returns the complete ordered history without pagination. It is intentionally unbounded. |
| Data retention | Retain carts, events, projections and accepted-command records indefinitely. This release has no deletion or archival operation. |
| Cart size | Do not impose a business limit on distinct product lines or accepted changes in a cart. |
| HTTP success | Every successful command and query, including first addition and idempotent replay, returns `200`. Cart creation is incidental to the add-product-item task, so it does not use `201`. |
| Trust boundary | Run this version only behind an upstream boundary that authenticates and authorizes callers. Cart UUIDs and observation tokens are concurrency data, not credentials. Do not expose the service directly to untrusted callers. |

Domain events remain internal to the backend. This delivery exposes no external
event stream and includes no broker, outbox, publisher port or public event
contract. Internally stored domain events, event sourcing and event-driven
projections are part of the delivery.

The deployment model must place the backend on a private listener or network
reachable only through the trusted upstream boundary. Document this as a release
precondition and verify the deployment configuration. The backend does not infer
authorization from a cart UUID or observation token. In-service identity and
permission enforcement remain deferred; direct public ingress does not.

The adjacent `04b-event-store` project is useful as a technical reference, but
must not be copied unchanged. Its optional expected version, `:any` writes, and
server-derived concurrency expectation violate `SWR-005` and `SWR-013`. Every
change to an existing cart requires the opaque observation returned by a query.
The first addition is the sole exception because it generates the cart identity,
and conflicts are never retried automatically against newer state.

## 2. Architecture Decisions

### 2.1 Deployment and Module Shape

Build one JVM process with one configured persistence adapter: PostgreSQL,
SQLite, or memory. The monolith has explicit module boundaries:

| Module | Responsibility |
|---|---|
| `cart` | Cart domain, use-case slices, inbound and outbound ports, domain events, projectors, and driving/driven adapters. |
| `platform.http` | Ring/Reitit server, JSON middleware, route assembly, error mapping, and OpenAPI serving. |
| `platform.persistence` | Shared datasource, migration and transaction-lifecycle support used by concrete persistence adapters. |
| `platform.runtime` | Configuration, lifecycle, logging, health checks, and the composition root. |

Only `platform.runtime` constructs concrete adapters. Cart domain, projector,
port and handler namespaces do not depend on Ring, JDBC, lifecycle, logging,
environment variables or wall-clock APIs. Only cart adapter namespaces may
depend on transport and persistence technologies. Enforce these dependency
rules with namespace architecture tests or clj-kondo forbidden namespace
configuration.

### 2.2 Vertical Slices

Organize application behavior by actor task rather than by a single horizontal
service layer:

```text
src/cart/
  domain/aggregate.clj
  domain/project.clj
  observation.clj
  port/out/event_store.clj
  port/out/projection_store.clj
  port/out/idempotency_store.clj
  port/out/unit_of_work.clj
  slice/add_product_item/
    port.clj
    handler.clj
    adapter/in/http.clj
  slice/remove_product_item/
    port.clj
    handler.clj
    adapter/in/http.clj
  slice/view_cart/
    port.clj
    handler.clj
    adapter/in/http.clj
  slice/confirm_cart/
    port.clj
    handler.clj
    adapter/in/http.clj
  slice/cancel_cart/
    port.clj
    handler.clj
    adapter/in/http.clj
  slice/review_change_history/
    port.clj
    handler.clj
    adapter/in/http.clj
  adapter/out/persistence/
    memory.clj
    sqlite.clj
    postgres.clj
src/platform/
  http/router.clj
  persistence/datasource.clj
  persistence/migrations.clj
  runtime/config.clj
  runtime/system.clj
src/cart_backend/main.clj
```

Each slice owns its request model, application handler, response model, HTTP
driving adapter, and slice tests. Its `port.clj` is the inbound use-case
abstraction, its handler implements that abstraction, and its HTTP adapter calls
it. Shared code is limited to genuine invariants: the cart aggregate, pure event
projectors, observation codec, outbound persistence contracts, JSON envelope
conventions, and runtime infrastructure.

The outbound ports are implemented by the memory, SQLite and PostgreSQL driven
adapters. The composition root injects inbound port implementations into HTTP
adapters and concrete outbound adapters into handlers. No HTTP adapter implements
an event-store or projection port, and no use-case handler depends on a concrete
database namespace.

Each slice's inbound `port.clj` defines an explicit one-operation protocol. Its
handler record implements that protocol, and the HTTP adapter receives the port
implementation rather than the concrete handler. Outbound `defprotocol`
contracts are implemented by each persistence adapter. Pure helpers remain
ordinary functions; they are not ports because no adapter boundary crosses them.

### 2.3 Functional Core and Imperative Shell

The domain core is deterministic and side-effect free:

```clojure
(fold events)                  ; events -> cart state
(decide state command)         ; -> {:events [...]} or {:rejection {...}}
(evolve state event)           ; -> new state
```

The core receives all required values as data. It does not generate UUIDs, read
time, access storage, publish messages, serialize JSON, log, sleep, or retry.
Business rejection is returned as data. Unexpected programmer or corrupt-event
errors fail loudly rather than being translated into domain rejection.

For an existing cart, the imperative command shell performs this sequence:

1. Validate the strict transport shape, command request UUID and domain input;
   verify and decode the observation token's signature and cart binding without
   checking whether its represented revision is current.
2. Look up the command request UUID. For a stored accepted logical command,
   compare the semantic canonical command: return the stored business result
   when it matches, or invalid input when it differs.
3. Load the event stream through the event-store port.
4. Compare the token's expected stream state with the loaded stream.
5. Fold events and call the pure decision function.
6. Enrich proposed events with event ID, timestamp, schema version, and metadata.
7. Derive cart-view and history projection changes from those events using pure
   projector functions.
8. Atomically append, apply projection changes and record the command result
   using the observation's expected revision.
9. Return success with the complete new cart view and observation, or return
    conflict.

For a first addition, the same shell omits the stream read and observation steps.
The unit of work atomically checks the command request UUID, appends the first
`product-item-added` event, applies its projections, and records the successful
result. A repeat with the same canonical command returns that exact stored result;
a repeat with different input is invalid. All attempts using one request UUID
serialize globally. Identical concurrent deliveries create one cart; different
concurrent inputs make exactly one command create a cart and make every loser
invalid. A generated cart-identifier collision creates no partial rows and
is retried with a new UUID.

Observation authenticity is deliberately resolved before idempotency, while
observation currency is resolved after idempotency. The first successful use of
an observation makes that observation stale, so checking currency before replay
would turn a valid retry into a 409; accepting a forged marker before replay
would violate the invalid-input precedence in `SWR-022`.
Only an already accepted command receives replay treatment; the treatment of
invalid, rejected and conflicting commands is not stored, so their request UUIDs
remain available for later use.

Canonical command equality is semantic: parse declared values first, normalize
UUID values, and canonicalize structured data independently of JSON whitespace
or object-field order. Undeclared fields fail validation. Persist and replay only
the business response payload; generate correlation, date and tracing metadata
separately for each delivery attempt.

Do not retry an optimistic conflict. A retry against newer state would replace
the actor's observation with a server observation and break the central business
rule. The actor must call `view-cart` and issue a new command.

Query handlers are separate CQRS paths. They never call command handlers, load
aggregate streams, fold events, or append events. `view-cart` reads the cart-view
projection and `review-cart-change-history` reads the ordered history projection.

Projection functions are pure event handlers. The imperative unit of work stores
events and applies their projection mutations in one transaction, giving queries
read-after-write consistency without making projection logic part of the domain
decision function. Eventual projection delivery is not part of this release.

## 3. Domain and Event Model

Use immutable Clojure maps with namespaced keywords internally.

```text
Cart state
  existence: missing | present
  status: open | closed
  closure: nil | confirmed | cancelled
  items: product-id -> integer quantity from 1 through 1000

Commands
  add-product-item
  remove-product-item
  confirm-cart
  cancel-cart

Events
  product-item-added.v1
  product-item-removed.v1
  cart-confirmed.v1
  cart-cancelled.v1
```

The closure field is internal. Both confirmation and cancellation map to public
status `closed`. Event history exposes which closure event was accepted.

An add event contains the cart UUID, product UUID and quantity. A removal event
contains the same identifiers and removed quantity. Confirmation and
cancellation events contain the cart UUID. No command, event, state or projection
contains price data. Addition decisions use checked arithmetic and reject a
resulting per-product quantity above 1000. Storage metadata contains event ID,
accepted time, correlation ID, causation ID when available, and event schema
version.

The first accepted event has stream revision 1 and each later accepted event
increments it by one. The add/remove event quantity is the accepted delta, not
the resulting product total. Assign one backend-generated acceptance instant as
part of the atomic commit and use it for both event metadata and the history
projection row.

Pure projectors turn accepted events into two read models:

```text
Cart view projection
  cart-id, revision, public status, product UUIDs and quantities

Cart history projection
  cart-id, revision, stable change type, accepted-at, business data
```

Closed cart-view rows retain final items. History rows omit internal stream IDs
and persistence metadata. The HTTP presenter converts projected items to an
array sorted by the canonical lowercase string form of the product UUID; domain
logic and persistence do not depend on presentation order.

Rejected, invalid, and conflicting attempts do not produce domain events. This
keeps change history equal to the ordered record of accepted changes.

## 4. Observation and Concurrency Contract

An observation is the complete cart state returned by a successful cart query or
command together with the marker for the revision represented by that response.
For example, `view-cart` returns the cart identifier, status and items plus the
public `cart-observation` field. The field contains only the opaque marker; the
cart response and marker together are the actor's observation.

The marker lets an actor say, "apply this command only if the cart is still in
the state I received." The actor sends the cart identifier and marker with the
next command but does not send the old cart contents back. The marker is not the
cart identifier, a lock, a reservation or a server-side session. It has no
time-to-live: elapsed time alone does not affect it. It remains current until one
accepted command changes that cart, at which point it becomes stale and any new
command based on it receives a conflict. This holds regardless of what the newer
state is: a stale observation of a cart that a confirmation or cancellation has
since closed is `409 conflict`, not `422 cart-closed`, because `SWR-022` checks
observation currency before business rules. `422 cart-closed` is reserved for a
command carrying the cart's *current* post-closure observation. After a conflict,
the actor must view the cart and make a new decision from the newly returned
observation.

An exact retry of an accepted command is the deliberate exception in response
handling, not in observation state. Its input observation is stale after the
original success, but the same global request UUID and canonical command return
the stored original result before the observation is checked. A new request UUID
using that old marker receives a conflict.

Never expose or accept raw stream versions, `:any`, or
`stream-does-not-exist` as the observation marker in the HTTP API.

`SWR-019` requires only that a marker be system-authenticated and cart-bound, and
`SPEC2.md` decision 21 leaves the mechanism to this document. The mechanism
chosen here is a versioned, HMAC-signed token containing:

- the exact cart identifier
- the expected internal stream revision
- a token format version
- a signing-key identifier

The signature is not necessary for the database compare-and-swap itself; an
expected numeric revision is sufficient to prevent lost updates. It is necessary
for the stronger public rule that the expected revision must come from an
observation actually issued by this backend. The signature supplies authenticity
and integrity, not secrecy. An unsigned encoded revision would let a client
invent a guessed current revision and claim to have observed state it never read;
an unbound token could be moved from one cart to another. Signing the payload
proves the backend issued that revision for that exact cart. The backend can
therefore distinguish a fabricated, altered or wrong-cart marker, which is
invalid input, from an authentic older observation, which is a concurrent-change
conflict. The signing key is secret runtime configuration. The token remains
opaque to clients even though confidentiality of its contents is not a security
requirement.

Include a key identifier in the token and verify signatures against a configured
key ring. Because observations do not expire with time, normal key rotation must
retain an old verification key while observations signed by it can still be
presented. Removing a verification key necessarily invalidates its outstanding
observations and is therefore an exceptional operational action, not normal
time-based expiry.

Token generation need not reproduce the same bytes for repeated views of one
revision. Key identifiers, format versions or other non-semantic token details
may differ. Decode authentic tokens and compare their represented cart UUID and
revision; never compare marker strings to decide observation equality or
currency.

There is no public observation for a nonexistent cart. The first product
addition omits both cart identifier and observation; the shell generates the
identifier and appends with the internal event-store expectation `:absent`.
Every later mutation requires the cart-bound observation. Supplying only one of
cart identifier or observation to `add-product-item` is invalid.

## 5. HTTP API

Define OpenAPI before implementing adapters. All business endpoints are
task-based `POST` operations with JSON request bodies:

| Endpoint | Kind | Required body data |
|---|---|---|
| `POST /commands/add-product-item` | Command | First addition: `request-id`, `product-item`; existing cart: `request-id`, `cart-id`, `cart-observation`, `product-item` |
| `POST /commands/remove-product-item` | Command | `request-id`, `cart-id`, `cart-observation`, `product-id`, `quantity` |
| `POST /commands/confirm-cart` | Command | `request-id`, `cart-id`, `cart-observation` |
| `POST /commands/cancel-cart` | Command | `request-id`, `cart-id`, `cart-observation` |
| `POST /queries/view-cart` | Query | `cart-id` |
| `POST /queries/review-cart-change-history` | Query | `cart-id` |

Operational endpoints such as `GET /health`, `GET /ready`, and
`GET /openapi.json` are not business commands or queries and may use GET.

Use stable machine-readable response envelopes:

| Outcome | HTTP status | Required response meaning |
|---|---:|---|
| Success | 200 | Result data and, for cart state, the observation produced by that result. An idempotent replay may therefore contain an observation that is no longer current. |
| Invalid input | 400 | `outcome=invalid`, stable code, and field errors. |
| Business rejection | 422 | `outcome=rejected` and a stable domain reason. |
| Concurrent change | 409 | `outcome=conflict`, `code=cart-changed`, and `next-action=view-cart-before-retrying`. |
| Unexpected failure | 500 | Correlation ID and a generic stable code; no internals. |

`SWR-008` fixes exactly four business rejections. Each gets one stable `422`
code, and no other condition may return `422`:

| Condition | Stable code | Applies to |
|---|---|---|
| Cart is closed | `cart-closed` | add, remove, confirm, cancel |
| Removal exceeds the held quantity | `insufficient-product-quantity` | remove |
| Confirmation of a cart with no product items | `cart-has-no-items` | confirm |
| Addition would push a held quantity above 1000 | `product-quantity-limit-exceeded` | add |

`cart-closed` covers confirm and cancel as well as content changes, matching the
widened `SWR-002`. Repeating a closure with a *new* request UUID is therefore
`422 cart-closed`, while repeating it with the *original* request UUID is a
`200` replay; the pipeline order in section 1 is what separates them.

`product-item` contains only UUID `product-id` and integer `quantity` from 1
through 1000. The resulting quantity held for that product may not exceed 1000.
An out-of-range requested quantity maps to `400 invalid`; exceeding the resulting
per-product limit maps to `422 rejected` with stable code
`product-quantity-limit-exceeded`.
Every object schema uses `additionalProperties: false`, including envelopes and
nested product items; price fields therefore receive the same unknown-field
rejection as any other undeclared field. `add-product-item` uses an OpenAPI
`oneOf` request schema: `request-id` is required in both variants; both cart
fields are omitted for the first addition and required for an existing cart.
Supplying a cart identifier that finds no cart produces the same `invalid-cart`
response regardless of its syntax. JSON is UTF-8 end to end.

Successful commands return the complete newly folded cart view and new
observation. A successful first addition also returns the generated cart
identifier. An accepted-command replay returns the exact logical result stored
for the original success, including its original observation; it
does not substitute the latest projection. This avoids an unnecessary follow-up
query while preserving the explicit refresh workflow after a conflict.

Every cart result uses this item shape:

```json
{
  "items": [
    {"product-id": "00000000-0000-0000-0000-000000000001", "quantity": 2},
    {"product-id": "00000000-0000-0000-0000-000000000002", "quantity": 1}
  ]
}
```

The array is sorted ascending by canonical lowercase product UUID. It has no
business ordering semantics; sorting makes responses and exact replay data
deterministic.

History success entries contain only `revision`, `change-type`, `accepted-at`
and `business-data`. Revisions start at 1. `accepted-at` is a UTC RFC 3339 string
for the instant at which the backend durably accepted the event. Add/remove
entries contain `product-id` and delta `quantity`; confirm/cancel entries use an
empty `business-data` object. The complete ordered array is returned without a
cursor, limit or pagination metadata. The API never returns stream IDs, database
positions, serializer metadata or idempotency records.

## 6. Event Store and Event-Driven Architecture

Define four outbound persistence contracts:

```clojure
(read-stream store stream-key)
;; -> {:exists? boolean :revision long :events [...]}

(commit! unit-of-work
         {:request-id ...
          :canonical-command ...
          :stream-key ...
          :expected ...
          :events [...]
          :projection-changes [...]
          :successful-result ...})
;; -> [:ok result] | [:conflict data] | [:idempotent original-result]

(read-cart-view projection-store cart-id)
(read-cart-history projection-store cart-id)
(find-command-result idempotency-store request-id)
```

`EventStore` owns stream reads. `UnitOfWork` atomically appends events, applies
projection changes and records the accepted command and exact successful result.
`ProjectionStore` serves queries. `IdempotencyStore` supports command-request
lookup. There is deliberately no unconditional append mode.

Implement all four ports with PostgreSQL, SQLite and in-memory driven adapters.
PostgreSQL is the production configuration, SQLite provides embedded persistent
operation, and memory provides a fast deterministic test/development mode. A
shared contract suite must prove behavioral parity.

The persistent adapters store event streams, idempotency results and projections:

```text
streams
  stream_id, stream_type, subject_id, current_revision
  unique(stream_type, subject_id)

events
  stream_id, stream_revision, event_id, event_type, event_version,
  event_data jsonb, event_metadata jsonb, accepted_at
  primary key(stream_id, stream_revision)
  unique(event_id)

command_requests
  request_id, command_type, canonical_input, canonical_input_hash,
  cart_id, original_result, accepted_at
  primary key(request_id)

cart_view_projection
  cart_id, revision, status, items
  primary key(cart_id)

cart_history_projection
  cart_id, revision, change_type, accepted_at, business_data
  primary key(cart_id, revision)
```

`request_id` is globally unique, not unique only within a cart. Revisions begin
at 1. PostgreSQL stores acceptance instants in `timestamptz`; SQLite stores a
normalized UTC representation that round-trips to the same instant; memory uses
`java.time.Instant`. HTTP serialization always emits UTC RFC 3339.

Each adapter checks the expected revision, appends every event, applies each
derived projection change and records accepted-command idempotency as one atomic
unit.
PostgreSQL uses one JDBC transaction or stored function; SQLite uses one
immediate write transaction; memory uses one atomic state transition. The first
addition uses the internal expectation `:absent`. A generated cart-UUID collision
creates no partial data and retries with a new UUID.

Concurrent uses of one command request UUID serialize on its primary key. The
same canonical command returns the stored original result; a different command
or input is invalid. This applies to additions, removals, confirmation and
cancellation. A losing expected-revision write using a different request UUID
returns conflict data and changes neither events nor projections. Constraints
backstop revisions, unique positions, UUIDs, per-product quantities from 1
through 1000, projection revision alignment and JSON object shapes.

The unit of work must serialize a request UUID before any stream append, then
recheck `command_requests` inside the transaction. This lock is global rather
than stream-scoped so different inputs racing on different carts cannot both
commit. PostgreSQL uses a transaction-scoped request-key lock, SQLite's immediate
write transaction serializes the check and write, and memory performs both in
one atomic transition. A lock or transaction for an unsuccessful command leaves
no persistent idempotency record.

These ports and adapters expose no cart-data deletion or archival operation.
Migrations must not add automatic expiry or cascading cleanup for event streams,
projections or accepted-command records. Capacity monitoring is operational;
there is intentionally no business constraint on product-line count, event count
or full-history response size in this release.

Domain events are the source of truth and drive aggregate state, which provides
the required event-sourced and internally event-driven behavior. Domain events
are not published outside the backend in this delivery. Do not define a broker,
outbox, publisher port, subscription API or public event schema. Any future
external subscriber requires a new use case and architecture decision; it must
not be introduced as a publish-after-commit callback or command-handler
dual-write.

Event formats are immutable. Changes require a new event version and a pure
upcaster. Unknown or corrupt events stop aggregate rehydration for the affected
stream and surface an operational error; they must not be silently ignored.

## 7. Clojure Technology Baseline

Use the adjacent project conventions unless an implementation spike disproves
them:

| Concern | Choice |
|---|---|
| Build and dependencies | Clojure CLI with `deps.edn`; Babashka task aliases |
| HTTP | Ring, Reitit, Jetty |
| JSON and schemas | Cheshire, explicit slice-owned request normalization, and OpenAPI as the external schema |
| Database | PostgreSQL and SQLite through `next.jdbc`; HikariCP pools |
| Migrations | Flyway deployment step for PostgreSQL; adapter-local Flyway migration for embedded SQLite |
| Lifecycle | Stuart Sierra Component at the composition root |
| Configuration | Aero plus environment overrides |
| Tests | `clojure.test`, Kaocha, test.check, Testcontainers PostgreSQL, embedded SQLite |
| Quality | clj-kondo, cljfmt, `git diff --check` |

Pin exact versions during scaffolding and commit the resolved dependency basis.
Keep request normalization and external schemas outside pure decision functions
so transport representation does not enter the domain core.

## 8. Delivery Iterations

Each iteration is a complete vertical slice through OpenAPI, HTTP adapter,
inbound handler, domain decision/query, event store, and automated evidence.

### Iteration 0: Decisions and Walking Skeleton

- Establish `docs/TEST_STRATEGY.md` with stable test-boundary terminology,
  small/medium/large isolation rules, no-Docker precommit and Docker-backed CI
  gates, and an explicit distinction between planned traceability and Verified
  acceptance behavior.
- Record the fixed section 1 decisions in OpenAPI and ADRs.
- Record ADRs for module boundaries, event sourcing, observation tokens,
  PostgreSQL concurrency, CQRS query strategy, global request-ID serialization,
  and the trusted deployment boundary.
- Create the Clojure project, lifecycle, configuration, quality tasks, health
  endpoints, OpenAPI skeleton, and empty PostgreSQL and SQLite migration paths.
- Add a requirements traceability table keyed by all 91 test case IDs, with a
  column recording the `SWR-008` outcome category each rejection case asserts.
- Implement the `SWR-022` evaluation pipeline as one shared function with its own
  unit tests, before any slice handler uses it.

Exit: the empty service starts locally, migrations run separately, architectural
dependency checks pass, and CI can run lint and tests.

### Iteration 1: Add Product Item and First View

Status: Delivered and verified.

Deliver `UC-01/S01`, `UC-01/S02` and `UC-02/S01`, comprising 14 acceptance
cases. `UC-01/S02` belongs here because first and existing-cart additions are two
forms of the same public command operation, and because `UC-01/S01/TC03` cannot
prove replay after a later cart change unless an existing-cart addition is
available through the public boundary.

Implement Iteration 1 in this order, keeping each completed stage green:

1. Define the immutable `product-item-added.v1` event and metadata shapes,
   canonical first-add and existing-add commands, successful result data and the
   two projection models. Build pure `fold`, `evolve`, add `decide` and projector
   functions with focused domain tests. Inject UUID, clock and observation-key
   dependencies into the imperative shell; none may enter the domain core.
2. Implement the versioned HMAC observation codec with supplied key-ring data.
   Prove round trips, alteration detection, cart binding, unknown versions and
   keys, retained verification keys during rotation, no time-based expiry and
   semantic comparison by represented cart and revision rather than marker bytes.
3. Define the event-store, projection-store, idempotency-store and unit-of-work
   ports plus one reusable outbound contract suite. Implement memory first so
   the add and view slices can be exercised before database concerns are added.
4. Add PostgreSQL and SQLite schema version 1 for streams, events, command
   requests, cart view and cart history. Replace the zero-migration assertions
   with exact version, ownership, repeatability and required-table assertions.
   Implement SQLite next and PostgreSQL last against the same outbound contract.
5. Wire the add command and view query inbound ports, HTTP adapters, routes and
   Component graph. `view-cart` must read only the cart-view projection. HTTP
   adapter tests use stub inbound ports to prove every declared outcome mapping;
   behavior acceptance uses the real application handlers.

The shared outbound contract must prove absent and expected-revision commits,
ordered stream reads, projection reads, atomic event/projection/idempotency
writes, exact stored-result replay, semantic request-ID misuse, and no partial
state after conflicts or injected storage failures. Unknown or corrupt events
must stop aggregate rehydration rather than becoming domain state. A generated
cart-UUID collision must create no partial state and must retry through an
injected UUID supplier.

Prove the following behavior through the public backend boundary:

- A first addition with no cart fields atomically establishes one cart and
  returns its UUID, projected contents and first observation. An existing-cart
  addition using its current observation aggregates quantity and returns the new
  projection and observation.
- Different first-add request UUIDs establish independent carts. Accepted first
  and existing additions replay their exact original business result after later
  changes without changing the current projection or history.
- Missing or malformed request IDs and request-ID reuse with non-equal input are
  invalid and create no state. A request ID used only by an invalid attempt
  remains available because only `commit!` writes `command_requests`.
- Concurrent equal deliveries accept one logical command and return one result.
  Concurrent non-equal first-add deliveries using one new global request UUID
  accept exactly one command; every loser is invalid and no second cart exists.
- Cart items are returned in canonical product-UUID order. Elapsed time alone
  does not expire a current observation. Viewing the returned cart is read-only
  and reports the committed projection immediately.

Run the reusable outbound contract against memory, SQLite and PostgreSQL. Run
all 14 acceptance cases through the Ring boundary against fresh migrated SQLite
and Testcontainers PostgreSQL. The PostgreSQL fixture owns a
`PostgreSQLContainer` and a one-shot Flyway `GenericContainer` on one private
Testcontainers network; it never relies on the developer Compose stack. Use a
suite-scoped migrated container and reset all application tables between cases,
while the dedicated migration suite retains its separate empty-database,
migrate-twice proof. Exercise the equal and non-equal request-ID races against
all three adapters, using barriers, bounded waits and separate database
connections for persistent stores. Keep one real-Jetty first-add-then-view smoke
test; do not duplicate the acceptance matrix over the network.

Exit: the actor can establish, add to and view an open cart through the complete
`add-product-item` operation, with atomic persistence, projection-only queries,
exact retries and adapter parity proven. All 14 traceability rows are Verified.

### Iteration 2: Manage and View Contents

Status: Refined and ready to implement.

Deliver 20 acceptance cases: all three cases in `UC-01/S03`,
`UC-01/S04/TC02` through `TC13`, all three cases in `UC-02/S02`, and both
cases in `UC-02/S05`. Do not deliver `UC-01/S04/TC01`, `UC-01/S04/TC14` or
`UC-02/S04/TC01` in this iteration. Those cases require a closed cart and move
to Iteration 4, where confirmation first provides a public way to establish one.
Acceptance setup must not insert a closed state directly or call an unpublished
closure handler.

Implement Iteration 2 in this order:

1. Refactor only the orchestration genuinely shared by commands concerning an
   existing cart into `cart.application.existing-cart-command`: authenticated
   observation loading, accepted-command replay, currency checking, event
   enrichment, projection derivation from the folded stream state, atomic
   commit and common outcome mapping. Keep first-add allocation in the add
   slice, request-shape normalization in each owning slice, and business
   decisions in the pure aggregate. Run the complete Iteration 1 suite before
   and after this refactor.
2. Add immutable `product-item-removed.v1` behavior to the pure aggregate and
   projectors. Removal below the held quantity reduces it; removal equal to the
   held quantity removes the line; removal above it returns
   `insufficient-product-quantity`. Folding an impossible or corrupt removal
   event fails rather than inventing state. History records the removed delta,
   not the resulting held quantity.
3. Implement the `remove-product-item` inbound port, handler and HTTP adapter.
   It uses the shared `SWR-022` pipeline, a semantic canonical command, signed
   observations, and the existing unit-of-work port. Accepted removals replay
   their exact original result after later changes. Equal concurrent deliveries
   using one request UUID commit once on memory, SQLite and PostgreSQL.
4. Complete strict request handling for both add forms, removal and view. Reject
   missing and non-canonical UUIDs, non-integer or out-of-range quantities,
   partial cart/observation pairs, unknown fields at every object level, price
   fields, unauthentic or wrong-cart observations, and cart UUIDs that find no
   cart. Uppercase and lowercase canonical UUID text remains semantically equal.
   Keep the slice-owned explicit normalizers established in Iteration 1 rather
   than adding a second runtime schema system beside OpenAPI.
5. Mount `POST /commands/remove-product-item` and retain projection-only
   `POST /queries/view-cart`. Prove immediate read-after-write visibility,
   canonical item ordering, stable revision across repeated views, and that a
   query appends neither an event nor a history entry. Validate every declared
   remove and view HTTP outcome, including `500`, against OpenAPI.

No schema migration is required: version 1 already supports versioned cart
events, removal history types and the complete projections. Extend the shared
adapter contract only where removal exposes a new generic persistence claim;
do not add a version 2 migration for a new event value carried by existing
columns.

For every invalid or rejected attempt, compare a complete before/after durable
snapshot: stream revision and events, cart-view projection, history projection,
accepted-command rows, and unrelated carts. Prove that rejected or invalid
attempts do not consume their request UUID, while reuse of an accepted UUID for
a different command, cart or input is invalid in the one global namespace.

Exit: an open cart can be added to, reduced to any non-negative contents, and
viewed safely. All 20 Iteration 2 traceability rows are Verified on SQLite and
PostgreSQL, with focused memory evidence and every Iteration 1 case still green.

### Iteration 3: Existing-Stream Concurrency

- Deliver six `UC-01/S05` cases and the shared conflict extensions. Defer
  `UC-01/S05/TC05` to Iteration 4 because it requires public confirmation setup.
- Race competing additions/removals against the same existing observation on
  separate database connections.
- Return stable conflict guidance and prove the actor can view and retry with a
  fresh observation. `UC-01/S05/TC04` makes this recovery path an explicit test
  rather than an implied one: conflict, re-view, resubmit with the fresh
  observation and a new request UUID, and assert the change is accepted.
- Complete content-command precedence with invalid input plus staleness
  (`UC-01/S05/TC06`) and a stale removal that would otherwise exceed the held
  quantity (`UC-01/S05/TC07`).

Exit: no stale content change can overwrite an accepted change, and outcome
precedence is proven rather than assumed.

### Iteration 4: Confirm Cart

- Deliver 21 cases: the 16 `UC-03` cases that do not require prior cancellation,
  `UC-02/S03/TC01`, `UC-01/S05/TC05`, `UC-01/S04/TC01`,
  `UC-01/S04/TC14`, and `UC-02/S04/TC01`. Confirmation supplies public setup
  for every closed-cart content and view assertion in this iteration.
- Add confirmation rules for empty, closed, invalid, and stale carts. Empty-cart
  confirmation is `422 cart-has-no-items`; confirming an already closed cart on
  its current observation is `422 cart-closed`.
- Apply command request idempotency to confirmation and return the complete
  original success when an accepted confirmation is repeated. `UC-03-A6` and
  `UC-03-A7` are new spec flows: cover the accepted replay, concurrent identical
  confirmations sharing one request UUID (`UC-03/S01/TC04`), and reuse of a
  succeeded confirmation UUID for a different cart or command
  (`UC-03/S01/TC05`).
- Cover the confirm-side conflict extensions that previously had no tests: two
  actors confirming from one observation (`UC-03/S04/TC02`) and conflict-then-
  re-view-then-reconfirm (`UC-03/S04/TC03`).
- Defer confirmation of a cancelled cart (`UC-03/S03/TC02`) and stale
  confirmation after cancellation (`UC-03/S04/TC04`) to Iteration 5, where the
  cancellation command exists through the public boundary.
- For `UC-01/S05/TC05`, hold an open-cart observation, confirm the cart through
  the public command, then submit an addition using the old observation. Assert
  `409 cart-changed`, not `422 cart-closed`, against every adapter.
- Cover the cross-cutting request rules on confirm, which `UC-01` already had but
  `UC-03` did not: missing or non-UUID request identifier (`UC-03/S05/TC03`),
  undeclared field (`UC-03/S05/TC04`), and altered, fabricated or wrong-cart
  observation token (`UC-03/S05/TC05`, which must be `400`, not `409`).
- Preserve final projected quantities, expose public closed status, and reject
  later content changes.

Exit: confirmation is atomic, terminal, and conflict-safe.

### Iteration 5: Cancel Cart

- Deliver 20 cases: every `UC-04` case, `UC-02/S03/TC02`, and the deferred
  `UC-03/S03/TC02` and `UC-03/S04/TC04` cases that require public cancellation
  setup.
- Cover cancellation after all quantities are removed, cancellation with
  contents, repeat closure, stale observations, and a concurrent cancellation
  versus content change.
- Apply command request idempotency to cancellation and distinguish an accepted
  retry (`200`, original result) from a new attempt to cancel an already closed
  cart (`422 cart-closed`). `UC-04-A6` and `UC-04-A7` are new spec flows: cover
  concurrent identical cancellations sharing one request UUID
  (`UC-04/S02/TC04`) and reuse of a succeeded cancellation UUID
  (`UC-04/S02/TC05`).
- Add the conflict recovery test `UC-04/S04/TC03`, and the cross-cutting request
  rules on cancel: missing or non-UUID request identifier (`UC-04/S05/TC03`),
  undeclared field (`UC-04/S05/TC04`), and unauthentic observation token
  (`UC-04/S05/TC05`, `400` rather than `409`).
- Prove stale cancellation of a cart since confirmed returns conflict before the
  closed-cart rule (`UC-04/S04/TC04`).

Exit: cancellation is atomic and terminal for every existing open-cart shape.

### Iteration 6: Support History

- Deliver every `UC-05` slice.
- Project accepted events into complete ordered history DTOs with one-based
  sequential revisions, the four fixed change types, UTC RFC 3339 acceptance
  times, delta quantities for item changes and empty closure business data.
- Prove rejected, conflicted and idempotently repeated attempts do not add
  history entries.
- Prove add and remove history quantities are deltas rather than resulting totals
  (`UC-05/S03/TC01`, `UC-05/S03/TC02`).
- `UC-05/S01/TC05`: prove two consecutive history reads return identical entries
  and leave cart contents, status and current observation unchanged.

Exit: support can distinguish confirmation from cancellation and explain every
accepted state transition.

### Iteration 7: Release Hardening

- Complete OpenAPI request/response validation for every declared status.
- Prove every successful business endpoint returns `200`, including first
  addition and idempotent replay; no success path returns `201`.
- Run all acceptance cases over real HTTP with migrated PostgreSQL and SQLite.
- Add structured logs, correlation IDs, readiness checks, graceful shutdown,
  datasource limits, request body limits, and secret handling.
- Verify deployment manifests expose the backend only to the trusted upstream
  boundary and document that cart UUIDs and observation tokens are not access
  credentials.
- Build one runnable artifact/container and document local start, migration,
  test, and rollback procedures.

Exit: all Must slices are Verified against the intended release artifact.

## 9. Test Strategy and Gates

| Layer | Purpose |
|---|---|
| Pure domain tests | Exercise decisions, folding, quantity bounds, invariants, and rejected-command no-event behavior without mocks or IO. |
| Observation codec tests | Prove valid, altered, fabricated and wrong-cart markers; prove elapsed clock time does not expire a marker; prove key identifiers select retained verification keys. |
| Slice handler tests | Exercise each inbound command/query port using memory persistence and deterministic clock/ID sources. Cover observation-authenticity validation before replay, exact accepted-command replay before currency checks, and reuse of UUIDs from unsuccessful attempts. Confirm and cancel carry the same request-identifier, unknown-field and token-authenticity cases as add and remove; none of the four may special-case the pipeline. |
| Persistence port contracts | Run event-store, projection, global command-idempotency and atomic-unit-of-work behavior against memory, SQLite and PostgreSQL adapters, including one-based revisions, identical acceptance instants and no expiry/deletion behavior. |
| Persistent race tests | Use barriers and separate connections to prove one winner for distinct commands sharing an observation, one accepted change for identical deliveries sharing a request UUID, and exactly one accepted logical command when otherwise valid non-equal inputs race on one global request UUID, including across different carts. Run these against SQLite and PostgreSQL. |
| HTTP contract tests | Validate every request and response against OpenAPI, including required command UUIDs, unknown fields at every object level, deterministic item ordering, fixed history shapes, UTC RFC 3339 timestamps, `400` versus `422` quantity outcomes, all-success `200`, malformed JSON, and UTF-8. |
| Outcome precedence tests | Drive the shared `SWR-022` pipeline with requests that fail more than one step at once, and assert the earlier step always wins: stale plus closed is `409`; stale plus unauthentic token is `400`; stale plus accepted replay is `200`; replay plus closed is `200`. |
| Acceptance tests | Implement all 91 `SPEC2.md` cases with their IDs visible in test names and run them through HTTP with PostgreSQL and SQLite. Every rejection case asserts its `SWR-008` outcome category and stable code, not just a non-2xx status. |
| Deployment-boundary tests | Inspect deployment configuration to prove there is no direct public ingress and only the trusted upstream boundary can reach the backend. |
| Architecture tests | Prevent the domain and slices from importing concrete HTTP, database, runtime, clock, or logging namespaces. |

Minimum local gates:

```text
bb test:domain
bb test:slices
bb test:persistence-memory
bb test:persistence-sqlite
bb test:persistence-postgres
bb test:http-contract
bb test:acceptance
bb check
bb ci
```

`bb ci` runs every gate, starts disposable PostgreSQL with Testcontainers,
applies PostgreSQL and SQLite migrations from empty databases, and tests the
exact release code against both persistent adapters.

## 10. Definition of Done

A slice is done only when:

- its SPEC2 test IDs pass through the public backend boundary
- applicable system-wide requirements have explicit test evidence
- command/query, domain event, and response schemas are documented in OpenAPI
- invalid, rejected, and conflicting paths append no events
- event append, projection updates and accepted-command idempotency record are
  one atomic operation
- semantic command equality ignores JSON representation details, and replayed
  business results exclude delivery-specific transport metadata
- the domain core remains deterministic and side-effect free
- cart, product and command request identifiers are UUIDs; all unknown fields
  are rejected; per-product quantity stays between 1 and 1000
- invalid requested quantities return `400`; additions exceeding the resulting
  quantity cap return `422`
- every rejection returns the `SWR-008` category and stable code the spec
  requires, and `422` is returned only for the four conditions listed in section 5
- the shared `SWR-022` pipeline decides every command outcome, and the overlap
  cases in section 9 prove the ordering
- every query reads a projection rather than folding the event stream
- successful changes are immediately present in both projections
- every successful command returns its complete resulting cart and observation
- every successful business response uses `200`; cart items are sorted by
  ascending product UUID in every response that carries them, including
  `view-cart`, regardless of insertion order
- history uses one-based revisions, fixed change types, delta quantities and UTC
  RFC 3339 acceptance times, and returns in full without pagination
- cart data and accepted-command results have no expiry, deletion or archival
  path; cart line and change counts have no business maximum
- no external domain-event publishing surface is included
- deployment exposes no direct untrusted ingress and documents the required
  upstream authentication and authorization boundary
- memory, SQLite and PostgreSQL pass the same persistence contracts
- logs and responses do not expose stack traces, secrets, or database details
- the traceability table identifies the implementing namespaces and tests

The release is done when all 24 slices and 91 acceptance tests are Verified,
PostgreSQL and SQLite migrations succeed from empty databases, both persistent
race suites are stable under repetition, and the built artifact passes the
system tests with both persistent configurations.

## 11. Explicitly Deferred

In-service authentication and authorization, catalog integration, stock, totals,
payment, orders, localization, snapshots, asynchronous projections, external
brokers, multi-tenancy, pagination, SLOs, and high-availability topology remain
outside this delivery. The trusted upstream boundary and absence of direct
public ingress are release preconditions, not deferred work. Add deferred
capabilities only through new use cases and ports rather than by expanding the
cart core or leaking infrastructure into existing slices.
