# Lab 25: vertical slices

[Lab 21](../lab21) put use cases at the centre and warned that ports and adapters do not tell you how to decompose a whole system. This lab supplies the missing axis: **business capability first, use case second, technical layers only inside a slice when they earn their keep.**

It follows Jimmy Bogard's argument in [*Modularizing the Monolith*](https://www.youtube.com/watch?v=fc6_NtD9soI): the useful alternative to both a ball-of-mud monolith and prematurely distributed services is one deployment containing modules whose boundaries are explicit, testable and difficult to bypass.

```bash
bb demo     # starts a real Postgres with Testcontainers
```

```text
  One deployment. Two modules. Two database owners.

  1. Catalog changes today's vanilla price to €3.00.
     Catalog committed its product and outbox together.

  2. Ordering cannot peek at Catalog's table.
     Ordering has not received a price for this product

  3. The outbox publishes a public contract; Ordering copies it.
     messages delivered: 1
     order 1: €3.00 each, €6.00 total

  4. Catalog changes today's price to €4.50.
     Catalog current price: €4.50
     old order still says: €3.00
     new order says: €4.50
```

## The pendulum is asking the wrong first question

A traditional monolith often becomes dangerous because nothing inside it resists coupling: changing one feature breaks another for reasons nobody can see ([01:20](https://www.youtube.com/watch?v=fc6_NtD9soI&t=80)). Splitting that code into processes does not discover the missing boundaries. It adds networks, partial failure, independent data and operational coordination while preserving every mistaken dependency; guessing those boundaries too early is precisely why many microservice rewrites fail ([03:59](https://www.youtube.com/watch?v=fc6_NtD9soI&t=239)).

So the first target is a **good monolith**: one that is easy to change because it contains genuinely independent modules ([09:47](https://www.youtube.com/watch?v=fc6_NtD9soI&t=587)). This lab is still one Clojure process and one deployment. Its internal shape is the new idea.

## The axis of change should be visible

A horizontal tree groups code by what kind of technology it uses:

```text
controllers/       all delivery
services/          all orchestration
repositories/      all persistence
entities/          all domain objects
```

Adding “Place order” cuts across every directory. The elements that change together are stored apart, while unrelated handlers meet inside an `OrderService` that grows into a dumping ground.

Lab 25 instead looks like the acts the business performs ([12:03](https://www.youtube.com/watch?v=fc6_NtD9soI&t=723)):

```text
src/lab25/
  catalog/
    api.clj                    public module surface
    contract.clj               public price-changed message
    change_price.clj           request + validation shape + transaction + response
    get_product.clj            request + query + response
    outbox.clj                 Catalog-owned delivery state

  ordering/
    api.clj                    public module surface
    place_order.clj            request + price rule + transaction + response
    get_order.clj              request + query + response
    catalog_price_changed.clj  contract consumer + inbox + local projection

  platform/
    behaviour.clj              validation and observation wrappers
    bus.clj                    replaceable in-process delivery mechanism

  system.clj                   one composition root, one deployment
```

A change to the place-order use case starts and usually ends in `ordering/place_order.clj`. Its schema, SQL, response and small pure calculation are together because they share an axis of change. The architecture fitness test checks that these use-case files exist and that no top-level `controllers`, `services`, `repositories` or `entities` tree has reappeared.

## A slice is not a module

The terms solve different-sized problems:

- A **vertical slice** is one request from input to result: change a price, place an order, get an order, consume a price-changed message.
- A **module** is the encapsulation boundary around related slices: Catalog or Ordering. It owns a public API, contracts and data.

Slices inside a module may share code when genuine duplication appears. They may not reach behind another module's public surface. That is the strict contract Bogard requires of a real module ([13:01](https://www.youtube.com/watch?v=fc6_NtD9soI&t=781)).

`ordering/catalog_price_changed.clj` therefore requires exactly one Catalog namespace:

```clojure
[lab25.catalog.contract :as catalog-contract]
```

It does not require Catalog's API, handler or SQL. Catalog requires nothing from Ordering. `architecture_test.clj` reads every namespace and fails if either rule changes.

## The boundary reaches Postgres

Package discipline is useful, but a shared writable table is a secret public API. This lab extends ownership to the database ([20:38](https://www.youtube.com/watch?v=fc6_NtD9soI&t=1238)):

```sql
CREATE SCHEMA catalog  AUTHORIZATION catalog_module;
CREATE SCHEMA ordering AUTHORIZATION ordering_module;
```

The one application opens two datasource identities. Catalog's slices connect as `catalog_module`; Ordering's slices connect as `ordering_module`. The test is deliberately stronger than a source scan:

```clojure
(is (thrown? SQLException
             (jdbc/execute-one! catalog-datasource
                                ["SELECT count(*) FROM ordering.orders"])))
```

Postgres refuses the query. The modules share a server for operational simplicity, but not tables or write authority. Separate databases could strengthen the same boundary later; starting with separate schemas and roles keeps one deployment without pretending table ownership is a comment.

The SQL also stays inside the slice that needs it. There is no generic repository abstraction: `place_order.clj` reads `ordering.price_book` and writes `ordering.orders` directly in one transaction. That is deliberate procedural code, not an architecture failure.

## Duplicate meaning, not just data

Catalog's price and an order's price happen to contain the same integer. They do not mean the same thing:

| owner | field | meaning |
|---|---|---|
| Catalog | `current_price_cents` | what vanilla costs now |
| Ordering | `unit_price_cents` | what this customer agreed to then |

Changing today's menu must not rewrite yesterday's receipt. Ordering therefore owns both a local current-price projection and the price captured on each order. The demo changes vanilla from €3.00 to €4.50 and proves the old order remains €3.00.

This duplication is cohesion: each module holds the fact in the form its use cases require. Normalising both meanings into one shared product row would replace duplicated bytes with temporal coupling.

## Communication is a contract, not a table read

When Catalog changes a price it updates `catalog.product` and records a `:catalog/price-changed` integration message in `catalog.outbox` in the same transaction. The relay publishes that public contract. Ordering consumes it into `ordering.price_book` and records the message id in `ordering.inbox` in its own transaction.

```text
Catalog transaction                 Ordering transaction
┌─────────────────────┐             ┌─────────────────────┐
│ product             │             │ price_book          │
│ outbox message      │── publish ─▶│ inbox message id    │
└─────────────────────┘             └─────────────────────┘
```

There is no transaction across the modules. Labs [12](../lab12) and [20](../lab20) already established the consequence: publish-then-mark is at-least-once, so the receiver must be idempotent. A test delivers the same contract twice and gets `:duplicate` on the second attempt.

The bus is in-process today. Replacing it with a broker would change delivery mechanics and operational guarantees, not grant permission to bypass the contract. That makes extraction possible; it does not make extraction free.

## Commands and queries do not need one model

Bogard's CQRS rule is small here:

- `change-price!` and `place-order!` are commands. They validate intent, transact and return a command-specific result.
- `get-product` and `get-order` are queries. They select only the response their caller needs and do not reuse a mutable “Product” or “Order” entity.

Each slice has one request, one handler and one response ([12:03](https://www.youtube.com/watch?v=fc6_NtD9soI&t=723)). CQRS here does not mean two services, two event stores or a framework mediator. It means reads and writes are different acts and do not have to compromise around one shared object model.

## Start procedural, refactor under evidence

There is no `IProductRepository`, generic handler base class or port for every SQL statement. The slices begin as straightforward procedures. That keeps accidental complexity proportional to the problem and continues Lab 21's rule: use ports and adapters to protect difficult rules or volatile technology, not because a diagram has a ring to fill.

Procedural is a starting point, not permission for an endless method. Red-green-refactor is mandatory. When decision logic becomes difficult, extract a pure function or domain object **inside the capability that owns it**. `place-order/price-order` is the tiny example: it can be tested with values alone, while the whole slice is still tested through Postgres.

## Cross-cutting concerns wrap the slice

Validation and observation should not obscure every handler. Each slice owns its request schema; `platform/behaviour.clj` supplies wrappers composed at the module API:

```clojure
(behaviour/compose
  #(place-order/handle! context %)
  [(behaviour/observation audit :ordering/place-order)
   (behaviour/validation place-order/Request)])
```

The malformed request never reaches SQL, but the attempt is still observed. Transactions are not forced into a generic wrapper in this lab because each command knows exactly which module-owned writes must commit together; `jdbc/with-transaction` remains beside those writes.

## Test the slice; test difficult rules directly

The shared strategy from Lab 21 is still the reference:

| Test Type | Target | Uses Fakes? | Speed & Scope |
|---|---|---|---|
| **Behavior / Use Case** | Primary ports or public use-case APIs | Yes, for secondary ports only | Fast. Covers all business logic and domain rules. |
| **Adapter / Integration** | Secondary adapters such as repositories | No | Slower. Proves infrastructure mapping works. |
| **System / E2E** | Primary adapters such as an HTTP API | No | Very slow. A few smoke tests prove the wiring. |

Lab 25 exposes an intentional variation. `catalog.api` and `ordering.api` are its public use-case surfaces, but their slices talk directly to module-owned Postgres tables. There is no secondary repository port to replace with a fake. Consequently `vertical_slice_test.clj` is both behaviour-focused and integration-speed: it enters only through a module's public contract and observes responses and database-visible outcomes, while real PostgreSQL participates.

Inventing `IOrderRepository` solely to make the first row fast would contradict the lab's start-procedural rule. If a slice later earns a driven port, its use-case tests should fake that port and a separate adapter contract should exercise the implementation against Postgres.

`pricing_test.clj` tests the pure `price-order` rule directly with values. More complex invariants would get more such tests. This is a focused supplement to public use-case tests, not permission to assert private call choreography.

There is no separate repository-adapter suite because there is no repository adapter, and no System/E2E suite because this lab has not added a primary HTTP adapter. `database_boundary_test.clj` is a PostgreSQL integration test for schema permissions. `architecture_test.clj` is orthogonal: it verifies the chosen source-dependency policy, not product behaviour.

## Refactor before extracting

Finding boundaries by reorganising a running monolith is safer than betting a distributed rewrite on theoretical boundaries ([16:22](https://www.youtube.com/watch?v=fc6_NtD9soI&t=982)). The feedback is ordinary change: if Catalog and Ordering keep changing together, their contract or boundary is wrong; if one changes independently, the boundary is earning its name.

Only a concrete operational need—independent deployment, scaling, fault isolation or ownership—pays for distribution. If that need arrives, a module with an API, contract, outbox, inbox and owned schema is a far safer extraction candidate ([45:06](https://www.youtube.com/watch?v=fc6_NtD9soI&t=2706)). Until then, one process keeps calls, debugging and deployment simple.

## What this changes about Lab 21

Nothing about inward dependencies was wrong. The ordering matters:

```text
1. decompose the system by business capability
2. organise each capability around its use cases
3. protect a slice's pure rules or volatile edges when their complexity justifies it
```

Clean or hexagonal architecture can still live **inside** Catalog or Ordering. It is not the top-level map of the whole system. Vertical slices answer “what changes together?”; ports and adapters answer “which dependency should this use case not know?” They are complementary when applied at their proper scale.

## Deferred

HTTP endpoints for the new slices; Lab 23 already established how to add them without changing a use case. Authentication and authorisation at each module API; Lab 24 established the placement rules. A real broker and relay retry policy. Independent migration pipelines. Contract-version compatibility tests. Row-level security in addition to schema ownership. Refactoring an intentionally tangled service into these modules commit by commit—the migration technique deserves a lab larger than the destination.

## What's next

Do not extract a microservice merely because the module can be extracted. The next useful experiment is to measure a reason: deployment frequency, asymmetric load, failure isolation or team ownership. Without one, the modular monolith is the destination, not a waiting room.

## Running it

```bash
bb check    # lint and formatting
bb test     # behaviour, pure-rule, architecture and database-boundary tests
bb demo     # the price-copy story above; needs Docker
```
