# Reference: the domain event, in full

The [labs](README.md) introduce one idea each and stay deliberately small. This document is the other thing: a single place for the detail that would otherwise swamp [lab1](lab1), and that you want to hand someone designing a real event store rather than learning the vocabulary.

It assumes the definition from [lab1](lab1):

> **A domain event is a business fact that has already happened, published to nobody in particular, which cannot be refused.**

Read lab1 first. Come here when you need to decide what goes in an envelope, which id to use, or what to tell a DBA.

Claims are attributed to Evans, Young, Dahan, Microsoft or Fowler where they are theirs, and marked where the framing is this repository's own. [Sources](#sources) are listed at the end.

**Contents**

- [Where does each fact go?](#where-does-each-fact-go) — `:data`, envelope, or neither
- [Identity](#identity) — which id, and who generates it
- [What the stream answers that no single event does](#what-the-stream-answers-that-no-single-event-does)
- [What it won't answer](#what-it-wont-answer)

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
 :event/id          #uuid "018f7a3e-…"              ; which message        (lab4)
 :event/type        :flavour-sold                   ; what kind of fact    (lab1)
 :event/occurred-at #inst "2026-08-16T14:32:07"     ; when, in the domain  (lab1)
 :stream/id         #uuid "0f1c2b3a-…"              ; whose history        (lab7)
 :stream/version    17                              ; where in it          (lab7)
 :event/position    4102                            ; where in the log     (lab9)

 ;; data — the fact itself, in the language of the domain
 :data     {:flavour :vanilla}

 ;; metadata — about the message, not about the fact
 :metadata {:recorded-at    #inst "2026-08-16T14:33:01"
            :actor          {:type :user :id "till-2"}
            :correlation-id #uuid "cc79c083-…"
            :causation-id   #uuid "31dd15c7-…"
            :schema-version 2}}
```

No lab builds this whole map, and you shouldn't start from it either. Each key is introduced in the lab where it earns its place, and the sections below say what each one buys and what breaks without it.

### Layer 1 — `:data`

| Question | Why it belongs to the fact | How |
|---|---|---|
| **What happened** | It *is* the fact. Wrong granularity, and no metadata rescues you. | Past-tense verb in domain language |
| **When it took legal effect** | Statutory dates are set by law and routinely back-dated. Not "when the handler ran". | `{:effective-date #inst "…"}` — a business fact, not plumbing |
| **Why — the business reason** | The genuinely unrecoverable one. Microsoft lists capturing intent, purpose or reason as a primary reason to adopt the pattern. | `{:reason-code :rung-up-twice}` — codes query, prose doesn't |
| **Under what authority** | Where authority is itself legally meaningful, this is the whole point in twenty years | `{:basis {:type :statutory-power :authority "LEG-CA-1001-S123"}}` |
| **On whose behalf** | An agent filing for a company is a domain fact | Only if legally significant — the logged-in user is not this |

The last two rows are registry examples rather than truck ones, deliberately: an ice cream truck has no statutory basis for selling a cone, and pretending otherwise would teach the wrong instinct. The questions only bite in a regulated domain, and there they bite hard.

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
| **Which message is this** | `:event/id` | [lab4](lab4) — identity, and idempotency under at-least-once delivery |
| **To what** | `:stream/id` | [lab7](lab7) — Young requires at least one id on every state-changing message, because all are routed to an object |
| **Where in that history** | `:stream/version` | [lab7](lab7) — orders the fold *and* is the concurrency token |
| **Where in the whole log** | `:event/position` | [lab9](lab9) — projection checkpoints |
| **When ×2** | `:event/occurred-at`, `[:metadata :recorded-at]` | below |
| **Who acted** | `[:metadata :actor]` | below |
| **What caused this** | `[:metadata :causation-id]` | below |
| **What larger process** | `[:metadata :correlation-id]` | [lab3](lab3) sketches it; below |
| **Which schema version** | `[:metadata :schema-version]`, or the type name | below |

**Where in that history** deserves its dual role spelled out, because the second half matters more: `:stream/version` orders the fold, *and* it is your concurrency token. Microsoft: event stores use optimistic concurrency control and reject an append if the stream changed since it was read. Without it, two withdrawals against one balance both pass `decide` and both commit. Enforce it with a `(stream_id, version)` unique constraint.

**When ×2 — bitemporality.** Young via Fowler: a meal paid Tuesday but not transmitted until Friday. It *occurred* Tuesday and was *noticed* Friday, and only two timestamps can say so.

```clojure
{:event/occurred-at #inst "2026-08-16T14:32:07"       ; the cone was handed over
 :metadata {:recorded-at #inst "2026-08-16T14:33:01"}} ; the till said so
```

This lets you separate *what we believed on 5 August* from *what we now know was true on 5 August*. Take `occurred-at` from the application; take `recorded-at` from `now()` in the database — **never trust application clocks for append time.**

**Who acted** is a kind as well as an id:

```clojure
{:actor {:type :user   :id "USR-83721"}}
{:actor {:type :system :id :overnight-restock-process}}
```

The `:type` matters as much as the id. A process manager is not a person, and recording the examiner as having incorporated the company when one did is a false record. Young also suggests capturing IP address and permission level *at the time* — permission as it was then is unrecoverable later.

> ⚠️ **Store an opaque actor id. Never JWTs, tokens, or credentials.** A bearer credential in append-only storage can never be revoked from the record; it drags personal data into the one store designed to resist deletion; and it doesn't prove authorisation anyway — only that a token was pasted in.

**What caused this — causation id.** Young's scheme is three ids per message: its own, correlation, causation. Responding to a message, you copy its correlation id and take its message id as your causation id. That gives you both the whole conversation and what-caused-what.

Most of its value needs no lookup at all. Every event emitted from one command shares a causation id, so the id alone says *these three facts came from a single decision* — grouping, with nothing to dereference. It is also the standard idempotency key: before processing, check whether events with this causation id already exist, and skip if they do. Both work with no command store whatsoever.

So treat it as an opaque grouping token that happens to have been minted by the command. If you additionally want to look up the originating command itself, you need to have stored it — but that's a footnote, not a decision to make up front. And the event usually reconstructs what was asked anyway: `WithdrawMoney{account, amount}` → `MoneyWithdrawn{account, amount}`.

There *is* a real argument for keeping a command log, and it isn't this one. See [What it won't answer](#what-it-wont-answer) below.

**What larger process — correlation id.** The case that earns it: a transfer debits A, the credit to B fails, and a compensating step refunds A. (This is *saga* in its original 1987 sense — a long-lived transaction whose steps each have a compensating transaction. [Lab2](lab2#two-scoping-notes) covers why the word is best avoided in its other senses.) Months later — *why did A receive this deposit?* The refund sits in A's stream looking unexplained; only the correlation id ties it to a failure in a different stream. Set it once at the boundary and propagate everywhere. Seed it from your trace id, so you can pivot to telemetry without merging the two worlds.

**Which schema version — the year-three problem.** Your deserialiser must handle every schema you have ever written. Microsoft gives two places to put the version: **as metadata in the envelope**, or **as part of the event type name** (`:flavour-sold-v2`). The envelope is usually the better of the two, because it leaves the type name meaning one thing forever — but the type-name form has the advantage of being impossible to ignore.

Then a ladder of strategies, in Microsoft's order of preference:

1. **Tolerant deserialisation** — ignore unknown fields, default missing ones. Handles additive changes with no transformation of stored events.
2. **A version identifier** — consumers select handling logic from it.
3. **Upcasters** — transformation functions applied at deserialisation time, chained so application code only ever sees the latest version. Stored events stay unchanged.
4. **In-place migration** — rewriting history to the new schema. A last resort, because it breaks immutability and undermines the audit trail.

### Layer 3 — not in the event store

Pod name, handler class, SQL timings, exceptions, stack traces. These go to logs and traces.

The correlation id is the **bridge** between the two worlds. Don't merge them — an event store that accumulates operational detail becomes unreadable to the domain experts whose language it was supposed to be written in.

---

## Identity

Two things get called "the event's id", and they behave differently. Worth separating before choosing.

### The identity that already exists: `(stream-id, version)`

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

### The identity you generate: a UUIDv7

The globally unique handle on *this particular message*.

**Why v7 rather than v4:** it's time-ordered, so it indexes well. Random v4 keys scatter across the B-tree and cause page splits on every insert; v7 keys append to the right-hand edge — exactly the access pattern of an append-only store. Same 128 bits, same collision safety, better locality. [Lab4](lab4) implements it.

**Why not a database sequence:** you want the id assignable *before* the append succeeds — for retries, for setting the causation id on downstream messages, for client-originated ids. Young's point about client-originated UUIDs being valuable in distributed systems applies here too.

In Postgres, `gen_random_uuid()` is still v4; PG18 added `uuidv7()`. On earlier versions, generate it in Clojure or add a small SQL function.

### Store all three

They answer different questions, and none substitutes for another:

| | Answers | Used by |
|---|---|---|
| `event_id` (UUIDv7) | *which message is this* | idempotency, causation, cross-system references |
| `(stream_id, version)` | *which event in this history* | optimistic concurrency, replay, ordering ([lab7](lab7)) |
| `global_position` (bigserial) | *where in the whole log* | projection checkpoints, catch-up subscriptions ([lab9](lab9)) |

You'll want the global position the moment you write a projection reading across streams, because a projector checkpoints on it. It needs to be monotonic *as the reader sees it* — which a naive `bigserial` does not actually guarantee; see [the visibility gap](#the-visibility-gap-in-global_position). A per-stream version cannot do this job at all.

> ⚠️ **Don't make the UUID your clustered primary key *and* rely on it for ordering.** Ordering is `(stream_id, version)` within a stream and `global_position` across streams. The UUID is for identity, not sequence — even a v7, whose ordering is only as good as the clock that made it.

### Who generates it — the application or Postgres?

Not "application good, database bad". The rule underneath is:

> **The writer that owns retry semantics owns the id.**

For an event store reached over a network by an application that retries — which is the normal case, and gets *more* decisive in a globally distributed system, not less — that writer is the application. Here is the argument, and then the conditions that would flip it.

**The argument that settles it: retry idempotency.** An append can fail ambiguously — connection drops, timeout, the write may or may not have landed. If Postgres mints the id via `DEFAULT uuidv7()`, your retry produces a *different* id for the same logical event, and nothing can tell the two apart. If the application minted it before the first attempt, the retry carries the same id and a unique constraint makes the insert idempotent. Duplicate suppression becomes a database guarantee rather than a hope.

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

Decide before you write your first projection ([lab9](lab9)), because the failure is silent: the projection is simply, quietly missing an event, and nothing reports it.

### The split, stated plainly

This looks inconsistent with "never trust application clocks for append time" above, so:

| Field | Generated by | Why |
|---|---|---|
| `event_id` | application | must exist before the write, must survive retries |
| `stream_version` | application, enforced by a unique constraint | it *is* the expected-version check |
| `occurred_at` | application | when the fact occurred in the domain |
| `recorded_at` | Postgres `now()` | only the store knows when it stored it |
| `global_position` | Postgres `bigserial` | must be assigned by a single authority — with the visibility caveat above |

The rule underneath: **anything that must be stable across retries comes from the application; anything that describes the append itself comes from the database.** *(That framing is this repository's.)*

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

## What the stream answers that no single event does

**What was true at a point in time.** Replay to position N. Young permits a date-limited query but notes a production system generally should not be doing this — an investigative tool, not a runtime feature.

**How the decision was made.** Distinct from the above, and stronger: fold to position N−1, feed the command back into `decide`, and observe the outcome. Because `decide` is pure ([lab8](lab8)), the reconstruction is exact. The caveat is real, though — this only holds if you version the decider alongside the schema. Proving "correct under the rules as they stood" requires the rules as they stood.

**How we got here.** Evans's actual motivation: without events, the causes of state changes typically aren't explicit, and it's hard to explain how the system got the way it is.

**Questions nobody has asked yet.** Because events represent every action the system has undertaken, any model describing the system can be built from them. Ship a projection ([lab9](lab9)), replay from 2019, and have years of history on day one.

This last one is the property that separates events from an audit log.

---

## What it won't answer

**Refusals.** Twelve rejected filings leave no trace if `decide` returns `[]` on failure. Fraud patterns, applicant friction, and examiner consistency all live in the rejections. If they matter, model `ApplicationRejected` with its reason code **deliberately** — [lab5](lab5) and [lab8](lab8) both come back to this.

This is the real argument for a command log, and it's worth being precise about why it's weak. A command that produces no events is invisible in the event stream — no causation id helps, because there is no event carrying one. So if you want the rejected attempts, storing raw commands would give them to you. But it gives them to you as *requests nobody acted on*, in a store you cannot fold, mixed in with every command that succeeded. Modelling the rejection as an event gives you the same information as a first-class fact, in the language of the domain, in the store you already replay. Reach for the event first.

**Anything you didn't choose to record.** Event sourcing does not give you an audit trail. It gives you a history of the facts you chose to preserve. If you only ever wrote `:application-approved`, then in fifteen years nobody recovers who approved it, why, or under what authority.

**It isn't "legal-grade" by default.** Only for facts recorded, and only if schema evolution is solved: an event you can no longer deserialise is not evidence.

And the usual framing — "the database permanently erases previous states" — is a bit of a strawman. Postgres has temporal tables, and a registry almost certainly has statutory retention already. What CRUD actually loses is the **meaning** of changes. That is Evans's point, and a sharper one than "it erases data".

---

## Sources

- **Eric Evans**, *Domain-Driven Design Reference* (2015) — the Domain Event pattern: full-fledged part of the domain model, the selection filter, distinctness from system events, derived identity.
- **Greg Young** — CQRS documents and writing on event and command naming, ids on state-changing messages, client-originated UUIDs, per-aggregate version numbers, and event store design.
- **Udi Dahan** — commands are sent, events are published; validation versus business rules.
- **Microsoft**, [Event Sourcing pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/event-sourcing), Azure Architecture Center — intent over state delta, bugs producing events that persist, optimistic concurrency, at-least-once delivery and idempotent consumers, the schema-versioning ladder, partitioning by entity id.
- **Martin Fowler** — *Bitemporal History*, on occurred-versus-recorded time.
- **Rich Hickey**, [*The Value of Values*](https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/ValueOfValues.md) (2012) — place-oriented programming, and why a value has nothing to overwrite.

## Where to go next

- [lab1](lab1) — what an event is, and the envelope/data split this document builds on
- [lab4](lab4) — identity in code: UUIDv7, and why generating one is an effect
- [lab7](lab7) — `:stream/id` and `:stream/version`, with optimistic concurrency
- [lab9](lab9) — projections, checkpointing, and rebuilds
