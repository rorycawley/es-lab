# Lab 21: functional core, imperative shell

Twenty labs are verified by tests and **none of them starts.** This one does:

```bash
bb demo      # no Docker, no configuration, no container
```

But running was the easy part. The reason this lab exists is that the repository has been doing functional core / imperative shell for twenty labs without ever naming it — and an unnamed discipline is one nobody can check.

## The core was always there

`decide`, `evolve`, `react`, `announce`, `upcast` — every one of them is a pure function of its arguments. Labs 4 and 11 argued that id generation and clock reading are *effects* and belong in arguments; labs 8 and 12 kept the store out of the domain by returning plain values. Those were separate arguments, made one lab at a time, with nowhere to put the conclusion.

This lab gives them a shape:

```text
src/lab21/
  core/             ← pure. requires clojure.* and nothing else
    truck.clj         decide, evolve
    policy.clj        react
    contract.clj      announce
  port.clj          ← protocols: what the shell may ask the world for
  adapter/          ← the world
    postgres.clj      an event store
    memory.clj        another event store
    clock.clj         time, and fresh identity
  app.clj           ← the application layer: read, call core, write
  system.clj        ← Component: the one place that names concrete things
  demo.clj          ← output
```

The arrows point inward and never back: **shell → ports → core.**

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

That last one is not a joke. **Thinness is the measure of a shell**, so the lab measures it.

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

Read it looking for a business rule. There isn't one — and the test that forbids `if`, `cond` and `case` here is what keeps it that way. A conditional in the shell is a rule in a place that cannot be tested without a database, and a rule in two places is a rule that will disagree with itself.

## Ports, and what a port is not

Four protocols: `EventStore`, `Outbox`, `Clock`, `Ids`.

Note what is *absent*. There is no `TruckRepository`, no `save-truck`, no `find-by-id`. A port describes what the outside world can **do for you** — an event store appends and reads streams, and that is the whole of its vocabulary. A port shaped like your domain is not a boundary; it is the domain leaking outward with an interface drawn round it.

The `Clock` and `Ids` ports are the consolidation the earlier labs were heading towards. "Effect" is not a synonym for I/O: **anything that makes a function return something different for the same inputs** belongs out here, and a clock qualifies exactly as much as a database does.

## Two adapters, or it isn't a boundary

A port with one implementation is indirection with optimism attached. So there are two event stores — a map in an atom, and Postgres — and **one test suite runs against both**:

```clojure
(each-adapter
 (fn [app]
   (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
   (let [events (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"}))]
     (is (= [:flavour-sold :stock-depleted] (map :event/type events))))))
```

The tests never mention `next.jdbc`, a datasource, a container or a transaction. All of lab 19 and 20's impedance — JSON losing namespaces, `java.util.Date` needing conversion, a UUID arriving back as a string — is contained in `adapter/postgres.clj`, and that containment is the entire return on drawing the port.

Set `ESLAB_SKIP_DOCKER=1` and the in-memory half of the suite still passes on its own, which is itself worth knowing.

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

The hexagon is half-drawn. These are all *driven* adapters — a store, an outbox, a clock, things the application reaches out to. Nothing reaches **in**, which is why every command in this lab arrives as a literal map and is trusted.

[Lab22](../lab22) adds the driving edge, and with it the thing [lab2](../lab2) promised and no lab has implemented: validation, at the adapter, before the command object exists.

## Running it

```bash
bb demo     # the whole system, in memory
bb test     # both adapters (Postgres half needs Docker)
bb all      # setup, check, test
```
