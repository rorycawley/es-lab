# Lab 21: functional core, imperative shell

Twenty-one labs are verified by tests and **none of them starts.** This one does:

```bash
bb demo      # no Docker, no configuration, no container
```

But running was the easy part. The reason this lab exists is that the repository has been doing functional core / imperative shell for twenty labs and only ever named half of it. [Lab0](../lab0) argued the principle — the domain model touches no machinery — and asserted it for one namespace. Twenty labs then obeyed it by hand, with no shape to put it in and nothing checking the parts lab 0 could not see: where the effects went, which direction the dependencies ran, who was allowed to name a database.

## The core was always there

`decide`, `evolve`, `react`, `announce`, `upcast` — every one of them is a pure function of its arguments. Labs 4 and 11 argued that id generation and clock reading are *effects* and belong in arguments; labs 8 and 12 kept the store out of the domain by returning plain values. Those were separate arguments, made one lab at a time, with nowhere to put the conclusion.

This lab gives them a shape:

```text
src/lab21/
  core/             ← pure. requires clojure.* and nothing else
    truck.clj         decide, evolve
    policy.clj        react
    contract.clj      announce
  port.clj          ← driven ports: what the use cases ask the world for
  adapter/          ← the world
    postgres.clj      an event store
    memory.clj        another event store
    clock.clj         time, and fresh identifiers
  app.clj           ← the use-case surface: read, call core, write
  system.clj        ← Component: the one place that names concrete things
  demo.clj          ← output
```

The important arrows are source dependencies, not the direction of the calls at runtime. The core depends on nothing in this application. `app.clj` depends on the core and the output-port abstractions, while concrete adapters depend on those abstractions. `system.clj` sits at the outer edge and wires the choices together. Nothing in the centre points back out.

## Put the use cases at the centre

Ian Cooper's explanation of Clean Architecture starts one step before testability: **the application should be organised around what the business does, not the frameworks used to deliver it.** Its structure should “scream” its use cases rather than Spring, Rails, reitit or Postgres ([29:37](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=1777)). Clean Architecture combines ideas found in Alistair Cockburn's Ports and Adapters and Ivar Jacobson's Boundaries, Controllers and Entities, but the common centre is the application's behaviour.

In this lab that behaviour is loading a truck, buying a flavour, observing stock and reacting to depletion. `next.jdbc`, Component and the storage representation do not define any of those acts. They are details attached at the edge. The command types, `app/handle`, `app/stock` and `app/react` form the use-case surface; the pure functions underneath contain the decisions. [Lab23](../lab23#name-the-act-not-the-entity) carries the same idea to HTTP by naming endpoints after acts rather than database-shaped resources.

The mechanism is the **inward dependency rule**: source dependencies point towards the application and domain, never from them towards infrastructure ([51:44](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=3104)). When an inner use case needs persistence, time or identity, it describes that need as a port. An outer adapter implements the port, and `system.clj` injects the concrete choice at runtime. That is dependency inversion: Postgres depends on the application's abstraction; the application does not depend on Postgres ([25:00](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=1500)).

Testability and technology isolation follow from that rule. A test is another driver of the use-case surface, so it can call the application with an in-memory implementation and no server or database ([33:12](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=1992)). Frameworks and persistence choices can be made at the last responsible moment, or replaced when they age, without rewriting the business decisions ([12:24](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=744)). Replacement is not free — schemas, migrations and adapter behaviour still matter — but their churn has somewhere to stop.

## What it still does not decide

Use-case centricity does not automatically discover the right use cases or group them into cohesive business capabilities. [Oliver Drotbohm's warning](https://speakerdeck.com/olivergierke/domain-centric-why-hexagonal-onion-and-clean-architecture-are-answers-to-the-wrong-question) still applies: a diagram with “domain” in the centre can treat that domain as one opaque block. If its rings become the top-level structure of a large codebase — all controllers together, all ports together, all repositories together — a single business change cuts across every ring. That is like furnishing a house by putting every chair in one room and every table in another: each technical category is easy to find, while the things needed for one activity are scattered.

So the directory tree above is the inside of one small **truck capability**, not a blueprint for decomposing an entire system. In a larger system, slice by business capability first and encapsulate each slice behind its use-case API or events. Then use inward dependencies inside a slice whose rules or integrations justify them. The outside should see the capability, not its `core`, `port` and `adapter` folders. Drotbohm's [Sliced Onion Architecture](https://odrotbohm.github.io/2023/07/sliced-onion-architecture/) expresses that ordering: functional cohesion chooses the modules; technical layering protects each module internally.

That “when justified” matters. Every port, adapter and mapping is another moving part. A straightforward CRUD slice may be clearer when it talks directly to its database library. A capability with difficult invariants, long-lived rules or volatile external systems earns the extra isolation. Intrinsic business or integration complexity should pay for the accidental complexity of the boundary. The goal is high cohesion around use cases and low coupling to technology ([52:21](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=3141)); the pattern is a tool for reaching that goal, not a tax on every feature.

## Why protect a pure core

The strongest return is not the ability to replace Postgres on a whim. It is that the business rules have a home that is free of I/O, mutable state, clocks, frameworks and third-party dependencies. `truck/decide` can enforce invariants such as “stock cannot be sold below zero” and “selling the final cone also depletes the flavour” using only a command and current state. Every state-changing use case in this capability passes through that decision, so the invariants form its consistency mechanism instead of being repeated across controllers, services and repositories. Those rules are deterministic, fast to test with plain values, and cannot quietly vary with an adapter.

Purity does not create the right domain boundaries or make the rules correct. It makes the rules that belong to one capability explicit, composable and cheap to exercise. The imperative shell then coordinates effects around the decision without becoming a second home for business policy. That is why this lab draws the boundary.

## A test suite earns its value tomorrow

Proving the code works today is useful. The larger return is proving it **still works after tomorrow's change**. That requires a regression suite coupled to stable business behaviour rather than today's arrangement of helpers, classes and calls.

An implementation test asks *how* the answer was produced: whether a helper was invoked twice, whether one domain object called another, whether a repository's `save` method received a particular intermediate shape. Heavy interaction mocks make those steps easy to assert. They also make a harmless extraction, merge or algorithm change look like a regression, because the test has made the old structure part of the contract.

A behaviour test asks *what* the capability promises. It supplies a business request through a driving/input port and observes returned facts, public query state or outgoing messages. The whole inner hexagon collaborates normally and remains a black box.

| | implementation test | behaviour / use-case test |
|---|---|---|
| **focus** | helpers, call sequences, wiring | public requests and business outcomes |
| **coupling** | today's code structure | stable use-case contract |
| **refactoring** | breaks when the shape moves | stays green while behaviour stays true |
| **maintenance** | follows internal rewrites | changes when requirements change |

The litmus test is simple:

> If the module's internals were deleted and rewritten behind the same inputs and outputs, would this test need rewriting?

For `app_test.clj`, the intended answer is no. It requires `app`, not `core.truck`, `core.policy` or `core.contract`, and enters through the three primary ports: `app/handle`, `app/stock` and `app/react`.

```clojure
(app/handle application truck-1
            (command :load-truck {:flavour "vanilla" :quantity 1}))

(let [events (app/handle application truck-1
                         (command :buy-flavour {:flavour "vanilla"}))]
  (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
  (is (= {"vanilla" 0} (app/stock application truck-1))))
```

There is no mock of `decide`, `replay`, the policy or the contract mapper in this **use-case suite**. Splitting one of those functions, combining them, replacing the fold or introducing a different internal domain model does not change this test. Changing the rule that the final sale emits a depletion fact does—and that is a real business change whose test should change deliberately.

That does not mean the pure core should be tested only through the application. Quite the opposite: **direct testing is one of purity's largest benefits.** `core_test.clj` calls `truck/decide`, `truck/replay`, `policy/react` and `contract/announce` with plain values—no system, adapter, fake, mock or fixture:

```clojure
(is (= [{:event/type :flavour-sold
         :data {:flavour "vanilla"}}
        {:event/type :stock-depleted
         :data {:flavour "vanilla"}}]
       (truck/decide buy-last-cone {"vanilla" 1})))
```

This is not the interaction-testing trap. The test asserts a business rule as `input → output`; it does not assert that `decide` called a helper twice. A refactoring behind the same pure function leaves it green. If the domain API itself is deliberately reshaped, its focused tests may move with it while the outer use-case tests prove that the capability's public behaviour survived.

## Fake the exits; do not mock the journey

The behaviour suite uses the in-memory `EventStore` and `Outbox` adapters, plus a fixed clock. These are **fakes**: small working implementations of the secondary/driven ports. They let the real use case and domain run without a server or database.

A fake is not an interaction mock. The tests never ask whether `append` was called exactly once or `read-stream` twice. They assert the observable result: stock changed, the right facts came back, the refusal left business state unchanged, or the fake outbox contains the announcements another module would receive. That leaves the use case free to change its internal journey.

Real infrastructure still needs proof, but it answers a different question. `adapter_test.clj` runs one neutral port contract against memory and Postgres: can an event be appended and read with its identity and metadata intact, is expected version enforced, and does an outbox message survive mapping? It deliberately contains no ice-cream rule.

The architectural testing split is:

| Test Type | Target | Uses Fakes? | Speed & Scope |
|---|---|---|---|
| **Behavior / Use Case** | Primary ports—`app/handle`, `app/stock`, `app/react` | Yes, for secondary ports only | Fast. Covers all business logic and domain rules. |
| **Adapter / Integration** | Secondary adapters—`EventStore`, `Outbox` | No | Slower. Proves infrastructure mapping works. |
| **System / E2E** | Primary adapters such as an HTTP API | No | Very slow. A few smoke tests prove the wiring. |

Lab 21 implements the first two. [Lab 23](../lab23) adds the HTTP primary adapter and keeps only a small number of socket-level checks.

The direct tests in `core_test.clj` are a focused supplement to the fast business-rule layer, not a fourth architectural test category. The primary-port suite proves the whole use case; direct pure-function tests give more precise feedback on important invariants at almost no cost. Both assert behaviour as inputs and outputs, and neither asserts internal call choreography.

`architecture_test.clj` is orthogonal to this split. It is a fitness function for a chosen code policy, not a product-behaviour test.

## The boundary is asserted, not drawn

A diagram in a README is a wish. `architecture_test.clj` reads the source and fails the build:

```clojure
core/truck.clj requires lab21.port
  — the core must not know about ports, adapters or Component

core/truck.clj calls (java.util.Date.
  — that is the clock, and it belongs in an adapter

app.clj contains (if
  — business logic has leaked out of the core
```

Those three messages are real: each was produced by deliberately breaking the rule and watching the test catch it. The checks cover the dependency direction, a list of effects the core may not reach for (`random-uuid`, `System/currentTimeMillis`, `atom`, `swap!`, `slurp`, `println`), that only `system.clj` ever names a concrete adapter, and that `app.clj` stays under forty lines.

These are intentionally **not** behaviour tests. They are coupled to a structural decision because enforcing that decision is their job. If the application is deliberately redesigned, these tests may be changed or removed; the behaviour suite is what says whether the redesign preserved the business promises. If only an accidental dependency arrow turns around, the fitness suite catches it before the architecture silently erodes.

That last measurement is not a joke. **Thinness is the measure of a shell**, so the lab measures it.

## The application layer, in full

Every function has the same three-part shape:

```clojure
(defn handle [{:keys [store outbox clock ids]} truck-id command]
  (let [history (port/read-stream store truck-id)      ; read   — a port
        state   (truck/replay history)                 ; core
        decided (truck/decide command state)           ; core
        events  (port/append store truck-id version …) ; write  — a port
        messages (into [] (mapcat contract/announce) events)]  ; core
    (when (seq messages) (port/enqueue outbox …))))    ; write  — a port
```

Read it looking for a business rule. There isn't one—and the structural fitness test that forbids `if`, `cond` and `case` here keeps it that way. The behaviour suite would catch a broken outcome wherever the rule lived; the fitness test adds a different guarantee, that rules do not quietly migrate into coordination code and acquire a second home.

## Ports point both ways

Cooper describes ports as the places where the application exposes its use cases, independently of any delivery mechanism ([32:00](https://www.youtube.com/watch?v=SxJPQ5qXisw&t=1920)). There are two directions:

- A **driving/input port** exposes something the application can do. In Clojure, `app/handle`, `app/stock` and `app/react` are already callable boundaries; they need no protocol merely to be ports. The demo and tests drive them directly. Lab22 adds an intake adapter, and Lab23 adds HTTP.
- A **driven/output port** states what a use case needs from the world. `EventStore`, `Outbox`, `Clock` and `Ids` are the four protocols in `port.clj`; the application calls them and adapters fulfil them.

An output port should name the capability the application needs, not the vendor that currently supplies it: `EventStore`, not `PostgresClient`. That does not prohibit a domain-shaped repository when a use case genuinely reasons in aggregates. It prohibits manufacturing one protocol per entity just because an architecture template has a “repositories” ring.

The `Clock` and `Ids` ports are the consolidation the earlier labs were heading towards. “Effect” is not a synonym for I/O: **anything that makes a function return something different for the same inputs** belongs out here, and a clock qualifies exactly as much as a database does.

## Fakes make behaviour fast; adapter contracts make technology honest

The business suite runs once against the in-memory fakes:

```clojure
(with-app
  (fn [application]
    (app/handle application truck-1
                (command :load-truck {:flavour "vanilla" :quantity 1}))
    (is (= {"vanilla" 1} (app/stock application truck-1)))))
```

The adapter contract then runs against both memory and Postgres using neutral example records rather than truck scenarios:

```clojure
(each-adapter
  (fn [{:keys [store]}]
    (let [[recorded] (port/append store stream-1 0 command [event])]
      (is (= [recorded] (port/read-stream store stream-1)))
      (is (= 1 (port/stream-version store stream-1))))))
```

All of labs 19 and 20's impedance—JSON losing namespaces, `java.util.Date` needing conversion, a UUID arriving back as a string—is contained in `adapter/postgres.clj` and exercised at that edge. A persistence mapping failure names the adapter contract; a stock-rule failure names the use case. Neither suite has to impersonate the other.

A second implementation is strong evidence that the abstraction is useful, not an admission price for every port. A single production adapter can still deserve a boundary when the dependency is slow, volatile or awkward to test. Conversely, inventing a port for stable, trivial CRUD merely because the diagram has one adds indirection without protecting meaningful rules. Here, the persistence impedance and the pure domain decisions are the justification; the fake demonstrates the testing result and the contract suite demonstrates substitutability.

Set `ESLAB_SKIP_DOCKER=1` and every behaviour test, every architecture check and the memory adapter contract still pass. Docker adds only the Postgres contract.

## Component, and why not an atom

Twenty labs passed `gen-id` and `now` down through every call, because there was nowhere to put them. Component gives them somewhere:

```clojure
(component/system-map
 :database (postgres/database config)
 :store    (component/using (postgres/store)  {:datasource :database})
 :outbox   (component/using (postgres/outbox) {:datasource :database})
 :clock    (clock/system-clock)
 :ids      (clock/random-ids))
```

Three things that a global atom would not give:

- **Dependencies are declared, not reached for.** `:store` needs `:database`; Component supplies it and starts them in order.
- **Lifecycle is explicit.** A test gets a fresh system and stops it afterwards, which is why the suite can run against a real database without leaking state between tests.
- **The composition root is one file.** Search the source for `postgres/store` and it appears in `system.clj` and nowhere else — a test asserts that. Swapping an adapter is a one-line change rather than an audit.

And the rule that keeps Component from spreading: **no core namespace requires it.** It is a composition tool and belongs at the edge, with everything else that is not a function of its inputs.

## What the demo shows

Six steps, printed in order: a load, two sales, the second of which emits *two* facts ([lab5](../lab5)); the two messages that depletion sends to other modules ([lab12](../lab12)); the policy reacting with a restock ([lab10](../lab10)); and a second pass doing nothing because nothing is new.

Every decision printed is the core's. `demo.clj` chooses only *when* to print — the same split as everywhere else, applied to the least important thing in the repository, because the split does not get a holiday for output.

## What's next

The hexagon already has drivers: the demo and tests call `app/handle`, `app/stock` and `app/react`. Those callers live inside the process and construct trusted command maps, however. There is no adapter for untrusted external input.

[Lab22](../lab22) adds that intake driver and implements the thing [lab2](../lab2) promised: validate the raw message at the adapter before allocating an identifier or constructing the internal command. The existing use-case surface and pure domain core stay unchanged.

## Running it

```bash
bb demo     # the whole system, in memory
bb test     # behaviour, fitness and adapter contracts (Postgres needs Docker)
bb all      # setup, check, test
```
