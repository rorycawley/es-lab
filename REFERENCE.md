# Reference: the domain event, in full

The [labs](README.md) introduce one idea each and stay deliberately small. This document is the other thing: a single place for the detail that would otherwise swamp [lab1](lab1), and that you want to hand someone designing a real event store rather than learning the vocabulary.

It assumes the definition from [lab1](lab1):

> **A domain event is a business fact that has already happened, published to nobody in particular, which cannot be refused.**

Read [lab0](lab0) for the model and [lab1](lab1) for the event definition. Come here when you need to decide what goes in an envelope, which id to use, how a fact gets from one module to another without a dual write, or what to tell a DBA.

[Lab0](lab0) is deliberately upstream of that definition. It establishes the pure business model whose changes later become events: a reduction to the rules that can change an answer, free of persistence, clocks and framework-shaped records. This reference starts at the first recorded fact, but every envelope and storage rule below exists to protect that model rather than replace it.

Claims are attributed to Evans, Young, Dahan, Microsoft or Fowler where they are theirs, and marked where the framing is this repository's own. [Sources](#sources) are listed at the end.

**Contents**

- [Before the event: the model](#before-the-event-the-model)
- [Where does each fact go?](#where-does-each-fact-go) — `:data`, envelope, or neither
- [Where does the aggregate boundary go?](#where-does-the-aggregate-boundary-go) — true invariants, atomicity, and contention
- [What makes a snapshot safe?](#what-makes-a-snapshot-safe) — coherent cursors, fold versions, and rebuilds
- [Identity](#identity) — which id, and who generates it
- [How does the fact leave the store?](#how-does-the-fact-leave-the-store) — the dual write, at-least-once, ordering, and retention
- [What the stream answers that no single event does](#what-the-stream-answers-that-no-single-event-does)
- [What it won't answer](#what-it-wont-answer)
- [Coordinators: policy, process manager, and "saga"](#coordinators-policy-process-manager-and-saga) — and why one of those words is best avoided
- [Where the business rules live](#where-the-business-rules-live) — and the two that constrain the architecture

---

## Before the event: the model

An event store cannot rescue the wrong model. [Lab0](lab0) makes that concrete by asking which truck attributes can change the answers to the business questions in scope. The pure model retains only those attributes and names its rules as functions of values; its deliberately persistence-shaped contrast complects those rules with rows, identifiers, mutation and a clock.

That distinction survives every later lab. Domain rules remain directly testable without infrastructure. Application code supplies identity and time, adapters perform I/O, and Lab25 groups the result by business capability and use case rather than treating technical layers as the system's decomposition. Event sourcing records changes to a model; it is not the model itself.

---

## Where does each fact go?

Once an event carries more than a flavour, every additional detail needs a home. One rule decides all of them: *(the rule is this repository's; the placements it produces are the sources')*

> Would a domain expert recognise this as part of **the fact itself**? → **`:data`**
> Is it about the **message** rather than the fact? → **envelope**
> Is it about the **machine**? → **neither**

The first bucket is `:data` and not `:payload`, deliberately — [lab1](lab1#why-data-and-not-payload) argues the distinction. A payload is a blob in transit; the data of a persisted fact is not in transit. `:payload` belongs to the integration message ([lab3](lab3)), where something really is being carried across a boundary.

### The complete shape

Every key this document discusses, in one map:

```clojure
{;; envelope — the frame, the same shape on every event
 :event/id          #uuid "018f7a3e-…"              ; which fact           (lab4)
 :event/type        :flavour-sold                   ; what kind of fact    (lab1)
 :event/occurred-at #inst "2026-08-16T14:32:07"     ; when, in the domain  (lab1)
 :stream/id         #uuid "0f1c2b3a-…"              ; whose history        (lab7)
 :stream/version    17                              ; where in it          (lab7)
 :event/position    4102                            ; where in the log     (lab9)

 ;; data — the fact itself, in the language of the domain
 :data     {:flavour "vanilla"}

 ;; metadata — about the message, not about the fact
 :metadata {:recorded-at    #inst "2026-08-16T14:33:01"
            :actor          {:type "user" :id "till-2"}
            :correlation-id #uuid "cc79c083-…"
            :causation-id   #uuid "31dd15c7-…"
            :schema-version 2}}
```

No lab builds this whole map, and you shouldn't start from it either. Each key is introduced in the lab where it earns its place, and the sections below say what each one buys and what breaks without it.

### Layer 1 — `:data`

| Question | Why it belongs to the fact | How |
|---|---|---|
| **The details of what happened** | They are the fact-specific values. Wrong granularity, and no metadata rescues you. | Domain names and JSON-safe values; the past-tense name itself is `:event/type` |
| **When it took legal effect** | Statutory dates are set by law and routinely back-dated. Not "when the handler ran". | `{:effective-date #inst "…"}` — a business fact, not plumbing |
| **Why — the business reason** | The genuinely unrecoverable one. Microsoft lists capturing intent, purpose or reason as a primary reason to adopt the pattern. | `{:reason-code "rung-up-twice"}` — codes query, prose doesn't |
| **Under what authority** | Where authority is itself legally meaningful, this is the whole point in twenty years | `{:basis {:type "statutory-power" :authority "LEG-CA-1001-S123"}}` |
| **On whose behalf** | An agent filing for a company is a domain fact | Only if legally significant — the logged-in user is not this |

The last two rows are registry examples rather than truck ones, deliberately: an ice cream truck has no statutory basis for selling a cone, and pretending otherwise would teach the wrong instinct. The questions only bite in a regulated domain, and there they bite hard.

**Stored values are data, not program symbols.** Use `"vanilla"`, not `:vanilla`, inside `:data` or `:metadata`. JSON and JSONB turn a keyword value into a string and cannot know to turn it back; `:key-fn keyword` only restores map keys. [Lab19](lab19) demonstrates the loss, and [lab24](lab24#json-shaped-facts-with-schema-driven-envelope-restoration) applies the resulting rule while reserving schema-driven restoration for inherent UUID loss. [Lab13](lab13#the-rung-that-was-not-hypothetical) deliberately preserves historic keyword-valued specimens and upcasts them on read: changing the rule does not authorise rewriting old facts. The deliberate exception is a discriminator such as `:event/type`: store it in its own `TEXT` column and coerce it once where code dispatches on it.

**Three different times, and they are not interchangeable.** The second row is the one people collapse:

| | Means | Lives in |
|---|---|---|
| `:effective-date` | when the fact takes effect in law or in the business | `:data` — it's a business fact, and can be back-dated |
| `:event/occurred-at` | when the fact happened | envelope |
| `:recorded-at` | when the store wrote it down | `:metadata` |

A filing made on the 5th, effective from the 1st, recorded on the 6th has three distinct dates and needs all three.

### Layer 2 — envelope

These are questions about the *message*, and most of them get a lab of their own. The table is the map:

| Question | Key | Where it's covered |
|---|---|---|
| **Which fact is this** | `:event/id` | [lab4](lab4) — the fact's identifier, distinct from an envelope's `:message/id` |
| **What kind of fact is it** | `:event/type` | [lab1](lab1#past-tense-and-its-load-bearing) — a past-tense discriminator; [lab19](lab19) stores it in its own column |
| **To what** | `:stream/id` | [lab7](lab7) — Young requires at least one id on every state-changing message, because all are routed to an object |
| **Where in that history** | `:stream/version` | [lab7](lab7) — orders the fold *and* is the concurrency token |
| **Where in the whole log** | `:event/position` | [lab9](lab9) — projection checkpoints |
| **Temporal axes** | `:event/occurred-at`, optional domain `:effective-at`, `[:metadata :recorded-at]` | [lab18](lab18) asks both bitemporal questions; below |
| **Who acted** | `[:metadata :actor]` | [lab24](lab24) — an opaque user or system identity, never a credential |
| **What caused this** | `[:metadata :causation-id]` | [lab10](lab10), with the idempotency limit closed by [lab20](lab20) |
| **What larger process** | `[:metadata :correlation-id]` | [lab11](lab11) folds a process across streams; below |
| **Which schema version** | `[:metadata :schema-version]`, or the type name | [lab13](lab13); below |
| **Which decision inputs** | retained command/ledger plus expected stream and rules versions | [lab18](lab18) reconstructs only when the complete inputs remain available |

**Where in that history** deserves its dual role spelled out, because the second half matters more: `:stream/version` orders the fold, *and* it is your concurrency token. Microsoft: event stores use optimistic concurrency control and reject an append if the stream changed since it was read. Without it, two withdrawals against one balance both pass `decide` and both commit. Enforce it with a `(stream_id, version)` unique constraint.

**Transaction time and valid time — bitemporality.** Young via Fowler: a meal paid Tuesday but not transmitted until Friday. It *occurred* Tuesday and was *noticed* Friday, and only two axes can say so.

```clojure
{:event/occurred-at #inst "2026-08-16T14:32:07"       ; the cone was handed over
 :metadata {:recorded-at #inst "2026-08-16T14:33:01"}} ; the till said so
```

This lets you separate *what we knew on 5 August* from *what current evidence says was valid on 5 August*. For an ordinary fact, occurrence time is also its valid time. A correction made on Friday can amend a Tuesday sale without falsely claiming the correction occurred on Tuesday: keep Friday as `:event/occurred-at`, reference the original sale id, and put Tuesday in a domain `:effective-at` value. Take occurrence/effective time from the validated application context; take transaction time from the database append—**never trust a client clock for `:recorded-at`.** For an exact transaction boundary, prefer a committed stream version or a proven-safe global cursor over an ambiguous wall-clock timestamp.

**Who acted** is a kind as well as an id:

```clojure
{:actor {:type "user"   :id "USR-83721"}}
{:actor {:type "system" :id "overnight-restock-process"}}
```

The `:type` matters as much as the id. A process manager is not a person, and recording the examiner as having incorporated the company when one did is a false record. This repository stores the opaque type and subject id only. If a regulated audit genuinely requires the IP address or permission level *at the time*, model those fields deliberately and account for their retention and personal-data cost; do not copy them wholesale from a token.

> ⚠️ **Store an opaque actor id. Never JWTs, tokens, or credentials.** A bearer credential in append-only storage can never be revoked from the record; it drags personal data into the one store designed to resist deletion; and it doesn't prove authorisation anyway — only that a token was pasted in.

Actor and correlation are deliberately separate. Correlation can be propagated through a conversation; authority must not be. When a sale triggers an automated restock, the restock's actor is a newly stamped system identity, not the customer who caused the policy to run. [Lab24](lab24#authority-does-not-propagate) makes that stamping rule executable.

**What caused this — causation id.** Young's scheme is three ids per message: its own, correlation, causation. Responding to a message, you copy its correlation id and take its message id as your causation id. That gives you both the whole conversation and what-caused-what.

Most of its value needs no lookup at all. Every event emitted from one command shares a causation id, so the id alone says *these three facts came from a single decision* — grouping, with nothing to dereference.

[Lab10](lab10#idempotency-using-the-causation-id) also uses it as an idempotency shortcut: before processing, check whether events with this causation id already exist, and skip if they do. That is sound only when the command is guaranteed to produce at least one event. [Lab5](lab5) permits a legitimate zero-event result, which leaves no causation id to find; [lab20](lab20#the-hole-in-lab-10) therefore uses a command ledger keyed by `:command/id`, written in the same transaction whether the result contains zero, one, or many events. The ledger retains enough request identity to reject reuse of an id for a different command. Causation is traceability metadata, not the general command-idempotency constraint.

So treat it as an opaque grouping token that happens to have been minted by the command. If you additionally want to look up the originating command itself, you need to have stored it; a command ledger proves that an id was handled but need not preserve the request body. A successful event often suggests what was asked — `WithdrawMoney{account, amount}` → `MoneyWithdrawn{account, amount}` — but that is not a lossless reconstruction contract.

There *is* a real argument for keeping a command log, and it isn't this one. See [What it won't answer](#what-it-wont-answer) below.

**What larger process — correlation id.** The case that earns it: a transfer debits A, the credit to B fails, and a compensating step refunds A. (This is *saga* in its original 1987 sense — a long-lived transaction whose steps each have a compensating transaction. [Below](#is-saga-a-third-thing) covers why the word is best avoided in its other senses.) Months later — *why did A receive this deposit?* The refund sits in A's stream looking unexplained; only the correlation id ties it to a failure in a different stream. Set it once at the boundary and propagate everywhere. Seed it from your trace id, so you can pivot to telemetry without merging the two worlds — seed, not alias: the trace is sampled and expires, while this question is still being asked years later. [lab26](lab26) keeps both in one message and persists only one of them.

**Which schema version — the year-three problem.** A supported history reader must handle every historical schema version it claims to support and reject future versions or semantics it does not understand. Microsoft gives two places to put the version: **as metadata in the envelope**, or **as part of the event type name** (`:flavour-sold-v2`). The envelope is usually the better of the two, because it leaves the type name meaning one thing forever — but the type-name form has the advantage of being impossible to ignore.

Then a ladder of strategies, in Microsoft's order of preference:

1. **Tolerant deserialisation** — for a known type and version, ignore compatible extra fields and apply deliberate defaults. Handles many additive changes without transforming stored events; it does not make unknown event types or future versions safe.
2. **A version identifier** — consumers select handling logic from it.
3. **Upcasters** — transformation functions applied at deserialisation time, chained so application code only ever sees the latest version. Stored events stay unchanged.
4. **In-place migration** — rewriting stored history. A controlled last resort requiring retained originals, provenance and verification because it replaces the representation originally preserved.

### Layer 3 — not in the event store

Pod name, handler class, SQL timings, exceptions, stack traces. These go to logs and traces.

The correlation id is the **bridge** between the two worlds. Don't merge them — an event store that accumulates operational detail becomes unreadable to the domain experts whose language it was supposed to be written in.

The rule runs in the other direction too, and costs more when broken. Telemetry is sampled, buffered, dropped under load and retained for days; every one of those is a feature there and a defect in a business number, so business counts come from the event log and its projections rather than from a metric. And a telemetry backend is a copy of your data outside the store you control: a personal field that [lab15](lab15)'s crypto-shredding can erase from an append-only history is not erased from a span attribute sitting in a vendor's retention window. Choose what leaves as an allow-list, not by dumping the request.

Trace context is the mechanism for the pivot, not a substitute for the correlation id. A W3C `traceparent` names one request's execution and belongs to the transport — inject it into the message headers, not into the event's own data — while the correlation id names a business conversation and is persisted. If you carry trace context through an outbox, capture it in the transaction that wrote the row, or the consumer joins the relay's trace rather than the request's. [lab26](lab26) builds both sides and asserts them.

---

## Where does the aggregate boundary go?

**First, what an aggregate is here.** There is no object and no base class. An aggregate in these labs is a triple of pure values and functions:

```clojure
initial-state                    ; what is true before anything happened
(evolve state event)   -> state  ; what a fact means             (lab6)
(decide state command) -> [event] ; what may happen next          (lab8)
```

That is the whole of it, and the two labs introducing the halves are six apart, so the assembled thing is easy to miss — [lab8](lab8)'s `truck.clj` is where all three first sit together, and no lab stops to name what they add up to.

`evolve` and `decide` are inseparable: `decide` checks a balance, `evolve` is what makes a balance, and neither is meaningful alone. A "domain model" holding one without the other has either rules with nothing to check them against, or state nobody is allowed to constrain.

What the triple must not acquire is a clock, a database, an id generator or a logger. Those belong to the caller that builds the envelope, which is why [lab21](lab21) separates them and why [lab32](lab32) asserts the rule with a fitness function over its own source rather than trusting review.

An event stream can implement an aggregate's history and optimistic-concurrency token, but the store cannot discover the aggregate for you. Start with a **true invariant**: a business rule that must hold when one transaction commits. Choose the smallest boundary whose own state can decide that rule, and modify one aggregate instance per transaction as the usual goal. Vernon presents “model true invariants,” “design small aggregates,” “reference by identity,” and “use eventual consistency outside the boundary” as rules of thumb, not a mechanical partitioning algorithm. [Lab16](lab16) measures the trade-off.

Wording changes the design. “The depot must never issue stock it does not hold” belongs to the depot; a separate truck credit may follow through an explicit protocol. “The depot debit and truck credit must commit atomically” spans both sides and needs one transactional boundary or another atomic mechanism. Splitting an invariant merely to reduce contention loses the rule. Conversely, putting unrelated work in one aggregate makes it share a version and creates avoidable optimistic conflicts.

An optimistic conflict is not a business refusal. It means the state used for a decision became stale, so the application must re-read and decide again; the retried command may still succeed. Conflict rate depends on workload overlap and transaction duration as well as boundary shape. A deterministic same-version batch can isolate the structural contribution, while a database race is still required to verify the adapter's atomic compare-and-append contract. In Postgres, `UNIQUE(stream_id, stream_version)` is a necessary integrity guard but not the complete contract: it rejects colliding stale writes yet permits a caller to jump to an unused future version. [Lab19](lab19) adds an atomic stream-head compare-and-set so stale and future expectations both fail.

---

## What makes a snapshot safe?

An aggregate snapshot is replaceable derived state: a cached fold of one stream through a particular `:stream/version`. Removing it must change replay work, never the answer. It is not a new source of truth, an aggregate boundary, or a substitute for retaining readable events. [Lab17](lab17) demonstrates the contract.

The snapshot state and cursor must describe **one coherent stream read**. If an implementation folds events through version 15 and separately asks a changing store for its current version, an intervening append can cause it to save state-at-15 labelled as version 16. A later load then skips event 16. Derive the snapshot cursor from the same event batch that produced the state, or obtain both under an equivalent consistency guarantee.

Record a fold version independently of event schema versions. Upcasting makes historical event representations readable; it does not make cached state produced by an old `evolve` shape compatible with the new fold. Reject mismatched fold versions and rebuild. Also reject malformed, negative, or future stream positions before folding events strictly after the accepted cursor.

These checks do not detect arbitrary corruption when bad state carries plausible metadata. Use the snapshot store's integrity guarantees, checksums or domain-specific state validation where that threat matters. Keeping snapshots optional still provides the recovery path: discard and replay authoritative history. The usual design writes them outside the append's critical path, accepting lag and occasional rebuilds rather than coupling an authoritative write to a cache; transactional snapshotting is possible when its extra latency and failure coupling are deliberate.

A projection checkpoint uses a related state-plus-cursor pattern, but the concepts are not identical. A projection answers a query over its subscribed feed, commonly across streams and by global position. An aggregate snapshot accelerates one stream's rehydration and advances by stream version.

---

## Identity

Rich Hickey defines identity as the stable logical entity associated with different immutable state values over time ([Values and Change](https://clojure.org/about/state)). An identifier is the value used to refer to that identity. A truck has identity across changing stock states; `:stream/id` is its identifier. An event is different: it is one immutable historical value, so `:event/id` names the fact rather than a changing entity. This section uses “event identity” only in that broader *which fact* sense.

Two things get called "the event's id", and they behave differently. Worth separating before choosing.

### The identifier that already exists: `(stream-id, version)`

Evans notes that an event typically carries a description, a timestamp, and the identity of the entities involved — and then:

> An identity can be derived from some set of those properties, so duplicate arrivals at a node can be recognised as the same event.

That's the hint people forget. You do not have to invent an identity; the store already contains one.

Young's version number is unique and sequential *only* within the context of a given aggregate, because aggregate root boundaries are consistency boundaries. So `(truck-1, 17)` — that truck's seventeenth event — names exactly one event, permanently:

```clojure
(juxt :stream/id :stream/version)
```

That's a **natural key**, and it exists whether or not you mint a UUID.

Compare it with the naive form of the same idea — type, occurrence time, and truck:

```clojure
(juxt :event/type :event/occurred-at #(get-in % [:data :truck-id]))
```

This also works, and has a sharp edge: two genuinely different sales in the same millisecond from the same truck become indistinguishable, and adding a field later can silently change what counts as "the same event." Widening the key doesn't rescue it either — two identical cones sold in the same millisecond are the *same value*, so no function of their properties can separate them. `(stream-id, version)` has neither problem, because the version is assigned rather than observed. ([lab1's tests](lab1/test/lab1/event_test.clj) assert both the working case and the collision.)

### The identifier you generate: a UUIDv7

The globally unique handle on *this particular fact*. It is distinct from the `:message/id` minted for each new envelope carrying that fact across a boundary ([lab4](lab4#but-lab3-said-ids-belong-on-the-message)).

**Why v7 rather than v4:** its timestamp prefix gives successively generated values much better index locality. Random v4 keys scatter throughout a B-tree; v7 keys normally cluster near the current-time edge. That is locality, not ordering: random suffixes do not order ids within one millisecond, and clocks across writers can disagree. [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.7) defines the layout; [lab4](lab4) implements the deliberately simple random-suffix form.

**Why not a database sequence:** you want the id assignable *before* the append succeeds — for retries, for setting the causation id on downstream messages, for client-originated ids. Young's point about client-originated UUIDs being valuable in distributed systems applies here too.

In Postgres, `gen_random_uuid()` is still v4; PG18 added `uuidv7()`. On earlier versions, generate it in Clojure or add a small SQL function.

### Store all three

They answer different questions, and none substitutes for another:

| | Answers | Used by |
|---|---|---|
| `event_id` (UUIDv7) | *which fact is this* | idempotent append retries, causation, cross-system references |
| `(stream_id, version)` | *which event in this history* | optimistic concurrency, replay, ordering ([lab7](lab7)) |
| `global_position` (bigserial) | *where in the whole log* | projection checkpoints, catch-up subscriptions ([lab9](lab9)) |

Do not collapse the other retry boundaries into `event_id`. A repeated **command** is recognised by `:command/id` and, generally, a command ledger; a repeated **integration delivery** is handled by an inbox keyed by recipient and the fact's `event_id`. A new `message_id` identifies a newly published envelope and therefore cannot deduplicate a republish; broker redelivery of an existing envelope retains its id. An inbox claim can be atomic with an effect only when that effect participates in the same local transaction; it does not make an email, payment, or remote service call exactly once. [Lab4](lab4#three-shapes-three-ids), [lab12](lab12#at-least-once-and-who-pays-for-it), and [lab20](lab20#the-inbox-and-what-lab-12s-consumer-could-not-do) exercise the three scopes.

You'll want the global position the moment you write a projection reading across streams, because a projector checkpoints on it. It needs to be monotonic *as the reader sees it* — which a naive `bigserial` does not actually guarantee; see [the visibility gap](#the-visibility-gap-in-global_position). A per-stream version cannot do this job at all.

> ⚠️ **Don't make the UUID your clustered primary key *and* rely on it for ordering.** Ordering is `(stream_id, version)` within a stream and `global_position` across streams. The UUID is an identifier, not a sequence — even a v7, whose ordering is only as good as the clock and monotonicity method that made it.

### Who generates it — the application or Postgres?

Not "application good, database bad". The rule underneath is:

> **The writer that owns retry semantics owns the id.**

For an event store reached over a network by an application that retries — which is the normal case, and gets *more* decisive in a globally distributed system, not less — that writer is the application. Here is the argument, and then the conditions that would flip it.

**The argument that settles it: append-retry idempotency.** An append can fail ambiguously—connection drops, timeout, the write may or may not have landed. If Postgres mints the id via `DEFAULT uuidv7()`, a retry produces a *different* id for the same logical event. If the application minted it before the first attempt, the exact retry carries the same id. A unique constraint detects repetition; the adapter must then verify that every id names the same intended fact and coordinate before returning the original rows. [Lab19](lab19) makes that full contract executable. This is separate from command idempotency, whose zero-event case needs [lab20's command ledger](lab20#the-hole-in-lab-10).

This is Young's client-originated-id argument one level down. Across unreliable links, ambiguous failures aren't an edge case — they're a Tuesday.

**The second reason:** you often need the id *before* the write completes. Downstream messages take it as their causation id; an outbox row references it. Database-generated means an extra round trip via `RETURNING` before you can construct anything pointing at it, so you can't build the whole write set in one pass.

**What you give up:** clock skew. UUIDv7 embeds a millisecond timestamp, so many application nodes across many regions means many clocks, and ids won't be globally monotonic.

Postgres's own implementation is better behaved, but less so than it first appears. PG18 uses the RFC's `rand_a` bits as a 12-bit sub-millisecond fraction that also acts as a counter, so generated ids increase monotonically even when the system clock moves backward or ids are produced faster than the clock ticks — **within a single backend process**. Across backends there is no such guarantee, so a connection-pooled application doesn't get global monotonicity from Postgres either. The property you'd be buying is narrower than the reason you'd be buying it.

And it costs nothing that matters, for two reasons:

1. **You must not order by the UUID anyway** — see the caution above. If skew broke your ordering, your ordering was already wrong.
2. **Index locality survives easily.** Even ±500ms of skew lands keys within a hair of the right-hand edge of the B-tree. Against v4's spread across the entire 128-bit space, you keep essentially all of the benefit.

Two smaller points also favour the application. **Testability**: an injectable generator gives deterministic tests, while `DEFAULT uuidv7()` is untestable without a database. And **purity**: `decide` must stay a pure function of command and state ([lab8](lab8)), so id generation belongs at the edge — in the handler that builds the envelope, not inside `decide` and not in a DDL default.

### When the argument stops applying

Every reason above is conditional, and knowing the condition is what tells you the rule doesn't extend to the other columns.

**It stops mattering when there is no ambiguous retry.** If the append happens inside a Postgres function or trigger — one round trip, no client-side retry loop, so from the application's point of view the transaction either committed or it didn't — the retry-idempotency argument evaporates. Same if the id is never referenced by anything outside the transaction that created it. Those are real designs; they just aren't this one.

**It reverses for `global_position`.** Anything requiring a *single authority* to assign it has to come from the database, because no application node can know what the others are doing. So the question is never "application or database" in general — it's which property you need, and the answers differ per column.

**Where "always" is fair.** Postgres 18 added `uuidv7()`, which puts a tempting `DEFAULT uuidv7()` within reach in the DDL. For an event store specifically: don't take it. You would get correct-looking ids that silently break idempotent retry, and you would discover it during a network partition — the worst possible moment to be learning this.

So: application-generated, no exceptions here. But hold it as *"because retries and causation need the id to pre-exist the write"* rather than as a bare rule, because that's the form that tells you it says nothing about `recorded_at` or `global_position`.

### `recorded_at`: `now()`, not `clock_timestamp()`

Which function matters, and the difference isn't cosmetic.

`now()` — the same thing as `transaction_timestamp()` and `CURRENT_TIMESTAMP` — returns the **transaction start** time and is constant for the whole transaction. `clock_timestamp()` returns the real current time and changes on every call.

For an event store, `now()` is right, and not merely by default. When one command produces three events appended in one transaction ([lab5](lab5), [lab8](lab8)), `now()` gives all three the same `recorded_at` — which is truthful, because they were committed atomically. `clock_timestamp()` would give them microsecond-apart values implying a sequence that does not exist. Ordering inside the transaction is `version`, not time.

```sql
recorded_at timestamptz NOT NULL DEFAULT now()
```

**`timestamptz`, never `timestamp`.** A naked `timestamp` in a globally distributed system is a bug waiting for a region to come online in another zone.

The mild imprecision — `now()` is transaction *start*, not commit — doesn't matter if your append transaction is short, which it should be. Wrap slow work in the same transaction and that gap widens.

**And you cannot order by it.** Three reasons, any one sufficient: every event in a transaction shares the value, so it can't order a batch; a transaction that started earlier can commit later, so the values don't follow commit order; and it's a clock reading, not a sequence. Order by `(stream_id, version)` within a stream, `global_position` across the log.

### The visibility gap in `global_position`

The trap: `bigserial` values are assigned at **INSERT** time, but rows become visible at **COMMIT** time. Transaction A takes sequence 100; transaction B takes 101 and commits first. A projector polling `WHERE global_position > last_seen` sees 101, checkpoints there, and **permanently skips 100** when A commits a moment later.

This bites every hand-rolled Postgres event store eventually, and gets worse the more concurrent your writers are. The usual fixes:

- take an advisory lock, or serialise the append, so sequence assignment and commit cannot interleave;
- track in-flight transaction ids (`pg_snapshot_xmin`) and refuse to advance the checkpoint past them;
- poll with a lag window and tolerate re-delivery — which you need anyway, since consumers must be idempotent.

PostgreSQL defines snapshot `xmin` as the lowest transaction id still active; lower ids are committed-and-visible or rolled-back-and-dead. That makes `xid < pg_snapshot_xmin(pg_current_snapshot())` a conservative settled boundary, though it can withhold already committed rows behind a long-running assigned xid. Decide before writing the first projection ([lab9](lab9)), because the naive failure is silent. [Lab19](lab19) reproduces it against Postgres 18 and implements this second fix.

### The split, stated plainly

This looks inconsistent with "never trust application clocks for append time" above, so:

| Field | Generated by | Why |
|---|---|---|
| `event_id` | application | must exist before the write, must survive retries |
| `stream_version` | proposed from expected version; enforced by atomic stream-head CAS plus uniqueness | rejects stale and future claims without gaps |
| `occurred_at` | application | when the fact occurred in the domain |
| `recorded_at` | Postgres `now()` | only the store knows when it stored it |
| `global_position` | Postgres `bigserial` | must be assigned by a single authority — with the visibility caveat above |

The rule underneath: **anything that must be stable across retries comes from the application; anything that describes the append itself comes from the database.** Exact append retry also requires retaining the same identified batch; regenerating ids by rerunning a handler is a different operation. *(That framing is this repository's.)*

### `global_position` and sharding

On a single Postgres, yes — the database has to assign it, because no other writer can. But sharding is exactly where the column stops being coherent, so it's worth being precise about what it's *for* first.

**It has one job: projection checkpointing.** A projector reading across streams needs a resumable cursor — *I've processed up to N, give me what's after* ([lab9](lab9)). That is the whole of it. It is **not** domain ordering and must never appear in your domain model; ordering that carries meaning is `(stream_id, version)`.

Because that's the only job, the question under sharding isn't "how do I preserve a global sequence" — it's "**what can a cross-shard projector checkpoint on**".

**What breaks.** Shard by aggregate id across N instances and there is no single sequence. Each shard has its own `bigserial`, and shard A's position 5000 has no relationship to shard B's 5000. You cannot merge them into a total order, and you shouldn't try: a global sequence reintroduces a single coordination point, which is the thing sharding existed to remove.

**Three answers, in the order to reach for them:**

1. **Don't shard the event store.** Worth saying plainly: append-only writes with no update contention scale a very long way vertically on Postgres. Sharding an event store is a decision for when you've measured a wall, not in anticipation of one.

2. **Route around it.** Choose a shard key that keeps a bounded context together, so most projections are single-shard and need no cross-shard cursor at all. Microsoft notes that event stores partition naturally by entity id, which is the lever. This is usually the better answer, because it's a modelling decision rather than infrastructure compensation.

3. **Vector checkpoints.** The projector holds a position per shard — `{shard-1 5000, shard-2 4812, shard-3 5133}` — and polls each independently. The honest answer, in that it accepts no total order exists rather than faking one. The cost: projections must be commutative across shards, so the projector cannot depend on events from different shards arriving in a particular relative order. Within a shard, and therefore within a stream, order still holds.

**Settle the topology before designing for any of this.** "Globally distributed" covers two very different shapes, and they have opposite answers. Multi-region usually means **one write region plus read replicas**, not sharded writes — and if that's the shape, the writer stays singular, you keep one `bigserial` and one total order, and the question never arises. Replicas serve queries and projections.

---

## How does the fact leave the store?

Everything above concerns one appended fact. This is the other half of a working system: a fact recorded in one place has to reach whoever reacts to it, and the mechanisms are not interchangeable. [Lab12](lab12) introduces the problem, [lab20](lab20) adds the receiving side, and [lab32](lab32) makes the whole path a single code path.

**The dual write is the failure to design against.** Append the event, then publish it, and there is a window where the state changed and nobody was told. Publish first and the window inverts. No retry policy closes it, because the process can stop between the two writes. The fix is that there is no second write: the event and a row in an outbox table commit in **one transaction against one database**.

This is worth stating as a property of a function signature rather than as a principle, because that is where it is enforced or lost:

```clojure
(outbox/enqueue! tx message)     ; correct — joins the caller's transaction
(outbox/enqueue! datasource msg) ; a dual write, with no flag to turn it off
```

Two events in the same aggregate, an outbox row, and a command-ledger entry are all one commit. Whatever the transaction cannot reach — an email, a card charge, a remote service — is outside the guarantee and needs its own idempotency boundary; an inbox claim is atomic with an effect only when that effect is a local write.

**Delivery is at-least-once, and that is the honest ceiling.** Exactly-once delivery across a boundary is not available; exactly-once *effect* is, and it is bought at the receiving end. An inbox keyed by the **fact's** `event_id` and a unique constraint turns redelivery into a no-op:

```sql
INSERT INTO consumer.inbox (event_id, …) VALUES (…) ON CONFLICT (event_id) DO NOTHING
```

Key it on the fact and never on the envelope. A republished message arrives in a new envelope carrying a new `message_id`, so deduplicating on that lets the second copy straight through. [Lab4](lab4#three-shapes-three-ids) draws the distinction and [lab12](lab12#at-least-once-and-who-pays-for-it) has a test for the version that gets it wrong.

This is also why the same id has to work at both ends. An ambiguous append retry must carry the id the first attempt used, which is the argument for generating `event_id` in the application rather than in the DDL — and it is that same stable id the consumer's unique constraint later recognises. Two requirements, one column, and it stops working if either end mints its own.

**Latency is a separate question from correctness, and mixing them is the common design error.** A relay that polls is correct and slow; its worst case is one polling interval. `LISTEN`/`NOTIFY` makes it fast, and it is tempting to treat the notification as the delivery mechanism. It is not one:

- `NOTIFY` is **at-most-once**. If no session is subscribed when it fires, the signal is discarded and nothing raises an error. Every deployment passes through that state on every restart.
- A notification arrives only after commit and only between transactions, so a long-running dispatcher transaction delays every signal behind it.
- The JDBC driver has no asynchronous callback and must poll the backend, on a dedicated connection that cannot come from a pool — `LISTEN` is session state, and a pooled connection is reset and handed to somebody else.

So the reconciler is not a fallback that a mature system outgrows; it is what makes a lost signal cost latency instead of an event. The property worth designing for is that **the notification and the timer trigger the same drain function**, so the fast path holds no delivery logic of its own and can be removed to prove it. [Lab32](lab32) runs its whole correctness suite three times — trigger absent, trigger present with nobody listening, and listener running — and requires identical outcomes.

Keep the signal empty. Postgres may fold identical notifications emitted in one transaction into a single delivery, which is exactly what a doorbell wants and would silently lose events if the signal identified a particular row.

**Ordering is not free and is not the default.** Claiming rows with `FOR UPDATE SKIP LOCKED` gives throughput and no ordering guarantee whatsoever: a concurrent relay takes the rows this one stepped over. To get per-aggregate order, claim the *partition* rather than the row — an advisory lock on a hash of the aggregate id, held for the transaction so it releases on commit or crash with no cleanup path.

The price is worth knowing before paying it: **in-order delivery and per-message failure isolation are not both available.** If a partition's messages are handled in one transaction and the third of five throws, the first two roll back with it. Committing the first two and dead-lettering the third would let the fourth and fifth apply to a state that never saw the third, which is the outcome the lock exists to prevent. What survives is isolation *between* partitions. A system claiming both properties has quietly stopped providing one.

**The queue and the history have opposite lifetimes.** An outbox row and an event row can hold nearly the same bytes and are not the same kind of thing. The outbox is a work list: once every consumer is finished with a row it is rubbish, and processed rows are pruned — 24 hours is the usual window. The event stream is the record, and nothing prunes it. A system that cannot tell the two apart either keeps its queues forever or prunes its history.

Two consequences follow. Prune by status *and* age, never age alone: a message pending for a day is the single most interesting row in the table, and deleting it turns a visible backlog into a silent loss. And a read model stays rebuildable after the queues are gone, because it is derived from facts rather than recovered from transport — which is the argument for this whole arrangement over a broker whose retention window is the only durability it has.

---

## What the stream answers that no single event does

**What was true at a point in time.** A stream prefix through version N is exact. Wall-clock questions require choosing transaction time or valid time, which are not the same question ([lab18](lab18)). Filtering and reordering events by effective time is safe only for a projection designed for that operation; it is not a general way to rehydrate an aggregate. Rare investigations may replay, while runtime temporal requirements deserve a purpose-built bitemporal projection or database model.

**The state a decision saw.** Retain the command's expected stream version and fold through that prefix. Event N−1 is only a shortcut when event N is the first fact from a one-event decision; a multi-event append breaks that inference. Reproducing the outcome also needs the original command, executable historical rules and every other explicit decision input. None is automatically recoverable from the event stream, and a rules-version integer is merely a pointer to behavior that must still exist. Given the complete inputs, `decide` is pure ([lab8](lab8)) and the outcome is exact. [Lab18](lab18#re-running-a-decision-and-what-that-requires) demonstrates the difference.

**How we got here.** Evans's actual motivation: without events, the causes of state changes typically aren't explicit, and it's hard to explain how the system got the way it is.

**Questions nobody has asked yet.** Any model derivable from the business facts you chose to record can be built later. Ship a projection ([lab9](lab9)), replay from 2019, and have years of history on day one.

This last one is the property that separates events from an audit log.

---

## What it won't answer

**Refusals.** Twelve rejected filings leave no trace if `decide` returns `[]` on failure. Fraud patterns, applicant friction, and examiner consistency all live in the rejections. If they matter, model `ApplicationRejected` with its reason code **deliberately** — [lab5](lab5) and [lab8](lab8) establish the choice, and [lab14](lab14#the-refusal-has-to-become-a-fact) records a refusal because a process manager needs to observe it.

This is the real argument for a command log, and it's worth being precise about why it's weak. A command that produces no events is invisible in the event stream — no causation id helps, because there is no event carrying one. So if you want the rejected attempts, storing raw commands would give them to you. But it gives them to you as *requests nobody acted on*, in a store you cannot fold, mixed in with every command that succeeded. Modelling the rejection as an event gives you the same information as a first-class fact, in the language of the domain, in the store you already replay. Reach for the event first.

**The original request or rules.** A causation id is a pointer, not a copy. A minimal command ledger may prove only receipt; [lab20](lab20#the-hole-in-lab-10) retains the target, type and request data as identity, plus correlation for traceability, so it can distinguish the same request from command-id reuse without turning trace metadata into an idempotency key. That still does not preserve old decision code, configuration, reference data, or the precise external input boundary. Likewise, event schema versions do not version decision logic. Exact reconstruction needs all of those things ([lab18](lab18#re-running-a-decision-and-what-that-requires)).

**Anything you didn't choose to record.** Event sourcing does not give you an audit trail. It gives you a history of the facts you chose to preserve. If you only ever wrote `:application-approved`, then in fifteen years nobody recovers who approved it, why, or under what authority.

**Personal detail you deliberately made unrecoverable.** [Lab15](lab15) minimises direct identifiers in events and crypto-shreds a justified residual field. An opaque subject id may still be pseudonymous personal data. The pure domain remains independent of encryption; a versioned, context-bound envelope is applied at the edge, and only a genuinely unavailable key produces the erased marker. Projections, caches, recipients, backups and escrowed key copies need their own coordinated purge and verification. This is an architectural technique, not proof that a legal erasure duty has been satisfied.

**It isn't "legal-grade" by default.** Only for facts recorded, and only if schema evolution is solved: an event you can no longer deserialise is not evidence. [Lab13](lab13) keeps a corpus and an upcast ladder specifically to make old facts readable.

And the usual framing — "the database permanently erases previous states" — is a bit of a strawman. Postgres has temporal tables, and a registry almost certainly has statutory retention already. What CRUD actually loses is the **meaning** of changes. That is Evans's point, and a sharper one than "it erases data".

---

## Coordinators: policy, process manager, and "saga"

[Lab2](lab2#two-scoping-notes) names the first two and defers the third here.

A **policy** ([lab10](lab10)) is the reactive rule between a fact and a request — Event Storming's *"whenever…"* sticky between an event and a command. A **process manager** ([lab11](lab11)) is a policy that holds state: *Enterprise Integration Patterns*' central unit that maintains state and determines the next step from intermediate results. Both own coordination rules while leaving each target aggregate authoritative for its state-dependent invariants. Lab11 reconstructs one manager from correlated aggregate facts, but that is an implementation choice: a process manager may persist its own state or own an event stream. A deadline also needs a timer or scheduler to wake the manager; passing `now` only makes the resulting decision deterministic.

Both have stable definitions. The third word does not.

### Is "saga" a third thing?

Not really — and the disagreement about it turns out not to be about sagas.

Three **independent** questions get asked of any coordinator, and different camps have each named one end of one of them "saga":

| Axis | One end | Other end |
|---|---|---|
| Does it hold state? | stateless reactor — a **policy** | stateful — a **process manager** |
| Does it cross a consistency boundary? | within one context, one transaction available | across several, no shared transaction |
| Does it need compensation? | no, roll back | yes, undo already-committed steps |

The axes are orthogonal. A coordinator can be stateless *and* cross-context *and* compensating; or stateful, intra-context, and never needing compensation. That is precisely why the definitions collide — each bundles a different axis into one word:

- **Garcia-Molina & Salem (1987)**, the origin, named the *third* axis. A saga is a long-lived transaction split into sub-transactions, each with a compensating transaction. It says nothing about messaging, state, or bounded contexts.
- **NServiceBus and much of the .NET world** named the stateful end of the *first* axis — which is where "saga = process manager" comes from.
- **Oskar Dudycz** names the *stateless* end of that same axis, with the process manager as the state machine. Directly opposed to the row above, on the one property that would tell them apart.

**Microsoft's CQRS Journey refused the word for coordinators entirely**, and their reasoning is the most useful thing written on it:

> The term *saga* is commonly used in discussions of CQRS to refer to a piece of code that coordinates and routes messages between bounded contexts and aggregates. However, for the purposes of this guidance we prefer to use the term *process manager*… There is a well-known, pre-existing definition of the term saga that has a different meaning from the one generally understood in relation to CQRS. The term *process manager* is a better description of the role performed by this type of code artifact.

They kept "saga" for the compensation mechanism, which is the 1987 meaning, and used "process manager" for the coordinator. Their remark that a process manager typically routes within a bounded context while a saga typically manages something spanning several is an observation about where each *tends* to show up — compensation is needed precisely where a transaction isn't available — not a definition of two rival kinds of coordinator.

Use a local ACID transaction whenever the invariant fits inside one consistency boundary. A compensating action is not a distributed rollback and does not recreate isolation: it is a new domain command applied after an earlier step committed, it records a new fact, and it can itself be refused. The aggregate owns that state-dependent refusal; the coordinator owns what the refusal means for the workflow. [Lab14](lab14) makes each of those distinctions executable.

So: **there is no coherent third thing.** There is a coordinator, which either holds state or doesn't, and there is compensation, which you need when you can't have a transaction. Those are the two ideas. `Policy` and `process manager` name the first pair with stable definitions; `compensating transaction` names the second without ambiguity. [Lab14](lab14) implements the compensating action and shows that compensation can itself fail. Reach for those three, and if someone says "saga", ask which axis they mean.

---

## Where the business rules live

With the aggregate and the coordinator both defined, the question of where a business rule is allowed to sit has a short answer:

> **Aggregates and policies.** Aggregates hold the rules that can *refuse*; policies hold the rules that *cause*.

Those two are the ones that constrain the architecture, and it is worth being explicit about why, because it is not symmetry:

- An **invariant** must be checked inside the transaction that appends, against state folded from real history, or it is decoration. That is what forces a consistency boundary, and why `UNIQUE (stream_id, version)` is load bearing rather than hygiene — without it, two commands both read a balance of 100, both permit a withdrawal of 100, and both commit.
- A **policy** must survive running twice, because delivery is at-least-once. That is what forces the inbox.

Neither constraint applies to the other two places domain knowledge legitimately sits. `evolve` is a total function of state and a fact that already happened; it cannot refuse and has nothing to make idempotent. A projection keyed on `event_id` absorbs repetition for free, which is why a **classification rule** — *a movement over 10,000 is reportable* — can live in a projection without ceremony. It is a real business rule and it needs no aggregate, because it decides how a fact is described rather than whether it may occur.

That last case is worth recognising rather than fixing. A module whose only rule cannot find an aggregate to live in is telling you it is read-side, and [lab9](lab9)'s rule applies instead: drop the read model, rebuild it, and every answer must be identical. [Lab32](lab32)'s compliance module is exactly this shape — no `decide`, no `evolve`, one threshold, and a replay test holding it to that promise.

What should never hold a business rule is the transport. A dispatcher, relay, inbox worker or listener that knows what a reportable amount is has taken a rule out of the domain and put it somewhere nobody thinks to read.

### Which of them may be configuration

A second question cuts across the same locations, and it is not "is this rule stable?" — nothing is. It is **what happens to answers already given when somebody changes the setting.**

| Location | Configurable? | Because |
|---|---|---|
| `evolve` | **never** | the same stream folds differently, nothing throws, and lab 17's fold version cannot detect it because the code did not change |
| `decide`, `isTerminal` | parameters only, **recorded** | a decision must stay reproducible, so whatever it read belongs on the event |
| policies | **yes — the best home** | the output is a request the target aggregate validates anyway, and a policy is forward-only |
| projections | current views yes; as-of views no | rebuild and reclassify are the same operation, which is right for *now* and wrong for *then* |

Two consequences are worth carrying even if you never build [lab33](lab33). A parameter that `decide` used goes in `:data` when it is part of what happened and in `:metadata` when it is only why the decision went that way — otherwise re-running the decision later reaches a different verdict and presents as a discrepancy rather than a defect. And a parameter that must be correct *as of* a past date has stopped being configuration: it needs its own history with effective dates, which is to say a stream, at which point the "configuration" is a projection and everything above applies to it.

The line underneath all of it is **values, not structure**. A threshold is a value. A predicate expressed as data needs an interpreter, and an interpreter makes the configuration a programming language with no type checker, no tests, no review and no `git blame` — whose characteristic failure is a misspelled field that matches nothing forever and reads as a quiet month.

**One kind of structure earns an exemption**, and the reason is narrow enough to state as a test. A process manager's transition table needs no interpreter — only a lookup — and every way of getting it wrong is decidable before it runs: a transition landing nowhere, an unreachable step, a dead end, a state both terminal and not, a command no module handles. If a shape can be checked exhaustively it may be data; if it cannot, it is a rules engine wearing a different hat. [Lab34](lab34) builds the five checks and the guard clause it refuses to add.

The price of that exemption is the thing a policy never has to pay. A process manager has **instances already running**, so a definition change reaches them — and the fix is the stamping rule again, one level up: an instance pins the version it started under, two definitions run at once, and a change that would strand somebody is refused rather than applied. Publishing a breaking version and migrating the instances inside it turn out to be inseparable, because each order is blocked by the other.

## Sources

- **Eric Evans**, *Domain-Driven Design Reference* (2015) — the Domain Event pattern: full-fledged part of the domain model, the selection filter, distinctness from system events, derived identity.
- **Rich Hickey**, [*Values and Change: Clojure's approach to Identity and State*](https://clojure.org/about/state) — identity as logical continuity across immutable state values, distinct from both state and the identifier used to name it.
- **Greg Young** — CQRS documents and writing on event and command naming, ids on state-changing messages, client-originated UUIDs, per-aggregate version numbers, and event store design.
- **Udi Dahan** — commands are sent, events are published; validation versus business rules.
- **Microsoft**, [Event Sourcing pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/event-sourcing), Azure Architecture Center — intent over state delta, bugs producing events that persist, optimistic concurrency, at-least-once delivery and idempotent consumers, the schema-versioning ladder, partitioning by entity id.
- **Martin Fowler** — *Bitemporal History*, on occurred-versus-recorded time.
- **Rich Hickey**, [*The Value of Values*](https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/ValueOfValues.md) (2012) — place-oriented programming, and why a value has nothing to overwrite.
- **Vaughn Vernon**, [*Effective Aggregate Design*](https://www.dddcommunity.org/library/vernon_2011/) (2011) — true invariants, small consistency boundaries, identity references, and eventual consistency outside the boundary.
- **PostgreSQL 18 documentation** — [sequence behavior](https://www.postgresql.org/docs/18/functions-sequence.html), [transaction snapshots](https://www.postgresql.org/docs/18/functions-info.html), and [date/time semantics](https://www.postgresql.org/docs/current/datatype-datetime.html) used by Lab19's real adapter.
- **Hector Garcia-Molina & Kenneth Salem**, *Sagas* (1987) — the original: a long-lived transaction whose steps each have a compensating transaction.
- **Gregor Hohpe & Bobby Woolf**, *Enterprise Integration Patterns* — the Process Manager.
- **Alberto Brandolini**, Event Storming — the Policy, the *"whenever…"* sticky between an event and a command.
- **Microsoft**, [*A Saga on Sagas*](https://learn.microsoft.com/en-us/previous-versions/msp-n-p/jj591569(v=pandp.10)) (CQRS Journey) — why they refused the word for coordinators.
- **Oskar Dudycz**, [*Saga and Process Manager*](https://event-driven.io/en/saga_process_manager_distributed_transactions/) — the stateless/stateful split, opposite to the NServiceBus usage.
- **Jimmy Bogard**, [*Modularizing the Monolith*](https://www.youtube.com/watch?v=fc6_NtD9soI) — vertical slices, strict module contracts, database ownership, and refactoring towards extractable boundaries.
- **Revolut Tech**, [*Recording more events… But where will we store them?*](Recording_more_events…_But_where_will_we_store_them.md) — a transactional log table rather than a broker, `LISTEN`/`NOTIFY` for the low-latency path, a reconciler resending anything unpublished, and 24-hour retention on the queue while the history is kept. Their stated reasons for rejecting Kafka were ad-hoc queries, querying by time, and guaranteed consistency between a state change and the event announcing it — not throughput.
- **PostgreSQL 18 documentation**, [`NOTIFY`](https://www.postgresql.org/docs/18/sql-notify.html) — at-most-once delivery, the folding of identical notifications within a transaction, and the size limit; [`SKIP LOCKED`](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE) and [advisory locks](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS) for queue claiming; and [READ COMMITTED](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED) on what is re-checked when a blocked update is unblocked, which is not what most people assume.
- **pgjdbc**, [listening for notifications](https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify) — the driver cannot receive asynchronous notifications and must poll, which is why a listener is a thread and a dedicated connection rather than a callback.

## Where to go next

Every lab, in order, with what it contributes to the material above. The [README](README.md) has the same list as a table with the one idea each lab introduces.

**The vocabulary** — what the words mean before any of them is stored.

- [lab0](lab0) — the reduced, pure business model whose changes the event history records
- [lab1](lab1) — what an event is, and the envelope/data split this document builds on
- [lab2](lab2) — a command: addressed to one handler, and refusable
- [lab3](lab3) — an integration message, and where `:payload` is the right word for something in transit
- [lab4](lab4) — identity versus identifiers in code: UUIDv7, and why allocating one is an effect
- [lab5](lab5) — cardinality: one command, zero-to-many events, zero-to-many messages

**The mechanics** — the aggregate, assembled from two halves six labs apart.

- [lab6](lab6) — `evolve`, and state as a fold rather than a thing you keep
- [lab7](lab7) — `:stream/id` and `:stream/version`, with optimistic concurrency
- [lab8](lab8) — `decide`, and the read-fold-decide-append loop the rest of this document assumes
- [lab9](lab9) — projections, checkpointing, and rebuilds
- [lab10](lab10) and [lab11](lab11) — causation, correlation, policies, and process managers
- [lab12](lab12) — the dual write, the outbox, and who pays for at-least-once

**The hard cases** — the sections above exist because of these.

- [lab13](lab13) — tolerant reads, schema versions, and upcasters
- [lab14](lab14) — compensating actions and observable refusals
- [lab15](lab15) — personal data and erasure in an append-only history
- [lab16](lab16) — aggregate boundaries, true invariants, and structural contention
- [lab17](lab17) — disposable snapshots, coherent cursors, and fold compatibility
- [lab18](lab18) — the two time axes and the rules required to reconstruct a decision

**Against a real database and a real network.**

- [lab19](lab19) — the Postgres schema and a demonstrated `global_position` visibility gap
- [lab20](lab20) — command ledgers, outboxes, and inboxes where metadata alone is insufficient
- [lab21](lab21) — ports, adapters, and the composition root that supplies identity and time
- [lab22](lab22) — validating at the edge, and why a schema is not a business rule
- [lab23](lab23) — HTTP as a driving adapter: name the act, not the entity
- [lab24](lab24) — actor metadata, authentication, and why authority does not propagate
- [lab25](lab25) — vertical slices, closed module APIs, module-owned schemas, and idempotent contracts between capabilities
- [lab26](lab26) — the other record: logs, traces and metrics, and the line between them and this one
- [lab27](lab27) — a search index as a projection, and the configuration that is its fold version
- [lab28](lab28) — network fallacies: bounded retries, deadlines, circuit breaking, provider-bounded idempotency, secure webhooks, and dead letters
- [lab29](lab29) — WebSub at the external boundary: topics as public resources, verified subscriptions, leases, and isolated fan-out
- [lab30](lab30) — multilingual legal-name lookup: Unicode folding, language-specific text search, German compounds, and a staged search cascade
- [lab31](lab31) — why a latency claim needs a declared workload, held-out inputs and a budget before it means anything
- [lab32](lab32) — the database as the event bus: one transaction, a doorbell that is removable, per-partition ordering, and the queries a retention window cannot answer
- [lab33](lab33) — which of these places may hold a rule as *configuration*, decided by whether changing it can reach the past
- [lab34](lab34) — the one exemption: a process definition as data, proved complete before it runs, and pinned once an instance is inside it
