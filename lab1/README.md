# Lab 1: an event

This lab explores what an event is, and how it's represented in Clojure.

> **A domain event is a business fact that has already happened, published to nobody in particular, which cannot be refused.**

Three clauses, and every property below follows from one of them. *(The reduction to three clauses is this lab's framing; the properties are drawn from Evans, Young, Dahan, Hickey and Microsoft, attributed where they appear.)*

The domain here is an Ice Cream truck — one, to begin with — and the event is the flavour of an ice cream that was sold.

---

## Because it has already happened

### Past tense, and it's load-bearing

Young is emphatic that events should always be verbs in the past tense — `CustomerRelocated`, `CargoShipped`, `InventoryLossageRecorded` — and that this matters because event names are part of the ubiquitous language. Avoid noun-shaped temptations like `Earthquake` or `Capsize`.

The grammar encodes the semantics. An imperative name signals the receiver may say no — that's a command, [lab2](../lab2). A past-tense name signals it may not. It is natural for a domain to reject an attempt to make a sale. It is not natural for it to tell a client that something in the past no longer happened.

Does your domain have a time machine?

### Immutable, at a real cost

There are no deletes. A delete is modelled as a **reversal** — a new event that undoes the effect of the first, while leaving a trail that the truck was once in the other state.

```clojure
{:event/type :sale-reversed
 :data       {:flavour "vanilla" :reason-code "rung-up-twice"}}
```

The cost enthusiasts skip: Microsoft is blunt that if a bug produces incorrect events, **those events persist in the store**, and fixing the bug in application code doesn't fix the historical events. You need compensating events or upcasters, and you need them for as long as the store exists.

Note what immutability does *not* do. It records rather than guarantees truth: an event can be wrong, and correcting a wrong fact means appending a correction, not editing the original. *(This distinction is the lab's.)*

In Clojure the mechanics come almost free, since maps are values:

```clojure
(assoc flavour-sold-vanilla :flavour "strawberry")
;; => a new map; flavour-sold-vanilla is untouched
```

Free in the language, but still a design commitment: nothing downstream may assume it can amend an event in place.

### It's just a value

There's a deeper reason an event doesn't change, and Rich Hickey's [*The Value of Values*](https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/ValueOfValues.md) names it.

He calls the alternative **place-oriented programming**: anytime new information replaces old information. An `UPDATE` is exactly that — a location reused, the previous occupant gone. And its origin is not a modelling decision but a hardware constraint. Memory and disk were scarce, so you overwrote the place. The constraint is gone; the habit stayed.

Read that way, immutability isn't a rule imposed on events. **A value isn't a place**, so there is nothing to overwrite. `assoc` returns a new map not because we forbade mutation, but because that's what values do. New information doesn't replace old information — it accretes, which is precisely why a mistaken sale becomes a sale *and* a reversal rather than an edit.

It also settles what an event *is* in code: nothing. No behaviour, no methods, no lifecycle. Just the data — a map. The significance lives in the name and in the fact that it was recorded, not in anything the event does. The functions that give it meaning ([lab6](../lab6)'s `evolve`, [lab8](../lab8)'s `decide`) take it as an argument and are defined elsewhere entirely.

That's convenient in Clojure, where a domain event needs no machinery to be one. It's worth noticing the convenience isn't incidental: a language whose default is values makes the shape of an event and the shape of a fact the same shape. *(That last observation is the lab's, not Hickey's.)*

---

## Because it is a business fact

### A full-fledged part of the domain model

Evans's phrase, and it's the claim most easily nodded past. It puts events **alongside entities and value objects** as first-class building blocks — not as infrastructure plumbing. An event is not a log line, not an audit record, not a DTO for getting data onto a queue. It gets everything the other modelling elements get: a name from the ubiquitous language, argued over with domain experts; deliberate design of what it carries and what it leaves out; a place in the model that outlives any particular technology.

The practical test: if you can rename an event freely because "nobody outside the code sees it," it isn't part of the domain model yet.

### Intent, not state delta

Microsoft's example: an event recording that **two seats were reserved** is more valuable than one recording that **remaining seats changed to 42**. The first tells you what happened; the second only tells you the resulting state. State-focused events reduce the event store to a change log with no business meaning.

```clojure
;; Intent — what the business did
{:event/type :flavour-sold       :data {:flavour "vanilla"}}

;; Delta — where a number landed
{:event/type :stock-level-changed :data {:flavour "vanilla" :to 2}}
```

The delta is recoverable from the intent: fold the history and the stock level falls out ([lab6](../lab6)). The intent is *not* recoverable from the delta — a level of 2 could equally mean a cone was sold, dropped, spoiled, or given away, and the event store no longer knows which.

### Name granularity is irreversible

`AddressCorrected` and `CustomerRelocated` produce identical state and answer different questions — one is a typo, the other is a person who moved. For the truck, the same shape:

```clojure
;; Two facts
{:event/type :price-corrected :data {:flavour "vanilla" :price 3.00M}}
{:event/type :price-increased :data {:flavour "vanilla" :price 3.00M}}

;; One name that covers both — and now nothing tells you which it was
{:event/type :price-changed   :data {:flavour "vanilla" :price 3.00M}}
```

Choose the coarse name and the distinction is gone **permanently**, because the information to reconstruct it was never written down. Splitting a name later only affects events recorded after the split; the old ones stay ambiguous forever.

Young's advice on where to start: use cases, since a command and a use case generally align.

### Selection is a filter

> Ignore irrelevant domain activity while making explicit the events that the domain experts want to track or be notified of, or which are associated with state changes in the other model objects.

Evans, and the part people skip. **Most of what your system does is not a domain event.** The filter has three prongs — tracked, notified of, or associated with a state change elsewhere:

```text
:flavour-sold          ✓  the till, the takings, and the stock all follow from it
:truck-restocked       ✓  changes what can be sold
:price-increased       ✓  the owner wants to know, and it changes future sales
─────────────────────────
button-clicked         ✗  a person touched a screen; the domain is indifferent
cache-evicted          ✗  a fact about the machine, not about ice cream
row-updated            ✗  describes the storage, not the business
customer-viewed-menu   ✗  unless someone actually wants that tracked — then it is
```

The last line is the honest one. The filter isn't a fixed list; it's a question you ask domain experts. "Do you care that this happened?" is the whole test, and the answer is usually no.

### Distinct from system events

Evans again: domain events are distinct from system events reflecting activity within the software itself — though a system event is often associated with one. The line he drew in 2015 is the same line [Layer 3](../REFERENCE.md#layer-3--not-in-the-event-store) draws: pod names and SQL timings are things that happened, and they are not domain events.

---

## Because it is published, not sent

Dahan: publishing is reserved for events, which state a fact, and **the publisher has no concern about what receivers do with it.** One logical sender; many receivers, or one, or zero. A subscriber cannot reject or cancel an event.

This survives every edge case:

```text
             ADDRESSEES     MAY THE RECEIVER REFUSE?
COMMAND      exactly one    yes
EVENT        any number     no
```

---

## Two scoping notes

**"Events are the source of truth" holds only under event sourcing.** A domain event in a CRUD system is a *notification*; the row is still the truth. Both are legitimate; they are not the same architecture, and the phrase gets borrowed across the boundary constantly.

**In code, an event is simply a data-holding structure** — structurally identical to a command. Only significance and intent differ. Which is exactly why the naming discipline above carries the weight it does: the shape won't save you.

---

## The shape

Sell a vanilla ice cream, and you have:

```clojure
{:event/type :flavour-sold
 :flavour    "vanilla"}
```

Two keys, two jobs. `:event/type` names *what kind of thing happened*, in the past tense. `:flavour` carries *what specifically happened*. The type is the part a reader dispatches on; the rest is the part they then interpret.

### Envelope and data

Those two jobs are different enough that events are divided into an **envelope** and its **data**:

```clojure
{:event/type :flavour-sold
 :data       {:flavour "vanilla"}}
```

Nothing has been added — the same facts are present. What changed is that the boundary is explicit. Without it, envelope keys and domain keys sit in one flat map with no rule saying which is which, and a domain field named `:type` or `:id` collides with the frame. With it, the domain names its fields whatever the domain calls them, and the envelope stays free to grow: [lab4](../lab4) adds `:event/id` without touching `:data` at all.

### Why `:data` and not `:payload`

The word is chosen deliberately.

A **payload** is a blob being carried somewhere, in transit. What we're modelling is not that. It's the data of a historical fact — kept as a durable record, not shipped anywhere.

```text
:payload    the right word for a transport or message envelope
:data       the right word for a persisted domain event
```

`:payload` reappears in [lab3](../lab3), where a fact really is being carried across a boundary and the word becomes correct.

---

## What an event carries

Beyond a description, an event typically carries **when it occurred** and **the identity of the entities involved** — often plus a second timestamp for **when it entered the system**, and **who entered it**.

```clojure
{:event/type        :flavour-sold                    ; what happened
 :event/occurred-at #inst "2026-08-16T14:32:07"      ; when, in the domain
 :data              {:flavour  "vanilla"              ; what specifically
                     :truck-id #uuid "0f1c2b3a-…"}   ; who was involved
 :metadata          {:recorded-at #inst "2026-08-16T14:33:01"
                     :actor       {:type "user" :id "till-2"}}}
```

**Two timestamps, not one.** The truck sold the cone at 14:32; the till got around to saying so at 14:33. These come apart constantly — an offline till syncing an hour later, a batch import of yesterday's paper records — and conflating them silently corrupts every question about *when*. "How many cones did we sell before lunch?" is a question about occurrence. "What did we know at closing time?" is about recording. One timestamp can only answer one of them — [lab18](../lab18) asks both and gets two different right answers.

**The entities involved.** `:truck-id` names which truck sold the cone. With a single truck that looks redundant, and it is — until [lab7](../lab7) grows a fleet, at which point it becomes `:stream/id` and decides which events belong to which history.

**The actor is a kind as well as an id.** A process manager is not a person, and recording one as the other is a false record. Store an **opaque** id — never a JWT, token, or credential: append-only storage cannot revoke one, it drags personal data into the store designed to resist deletion, and it proves only that a token was pasted in.

**A value in a fact is a string, not a keyword.** `"vanilla"`, not `:vanilla`. A keyword is a *program symbol* — it means something to the code that wrote it and nothing to anything else — and a recorded fact has to outlive that code, cross a wire, and sit in a database column. JSON, JSONB and every other common encoding turn a keyword into a string on the way out and cannot turn it back: `:key-fn keyword` restores *keys*, because their names are known in advance, and there is no equivalent for values.

[Lab19](../lab19) discovered that the expensive way, and this repository patched it three times before adopting the rule. The one keyword worth persisting is a **discriminator** the code branches on — `:event/type` — and that belongs in a column of its own, coerced once at the point of dispatch. Inside `:data`, use strings. [Lab13](../lab13) shows what it costs to change your mind later.

**Why is `:recorded-at` nested and `:event/occurred-at` not?** The top level holds the small fixed set every event must have: what kind of fact it is, when it happened, and — from [lab4](../lab4) and [lab7](../lab7) — which fact it is and where it sits in a history. `:metadata` is the open map for everything else *about the message*: how it came to be written down, what it was part of, what caused it. A key graduates out of `:metadata` when it becomes mandatory, not when it becomes important.

Deciding where each of these belongs — `:data`, envelope, or nowhere near the event store — is what [REFERENCE.md](../REFERENCE.md#where-does-each-fact-go) is for.

## Identity, briefly

Two things get called "the event's id", and they behave differently.

There is the one you **mint** — a UUID in the envelope, the subject of [lab4](../lab4). And there is the one the store **already contains**: `(:stream/id, :stream/version)` names exactly one event permanently, and exists whether or not you mint anything ([lab7](../lab7)).

Evans's hint is that identity can be derived from some set of an event's properties, so duplicate arrivals can be recognised as the same event. `(stream-id, version)` is the disciplined form of that, because the version is *assigned* rather than observed. A key derived from the event's own data instead — say type, time and truck — collides the moment two different sales share a millisecond, and the tests show it doing so.

Which to store, who generates it, and what a DBA needs to know: [REFERENCE.md](../REFERENCE.md#identity).

## Further reading

[REFERENCE.md](../REFERENCE.md) carries the detail this lab deliberately leaves out:

- **[Where does each fact go?](../REFERENCE.md#where-does-each-fact-go)** — `:data`, envelope, or neither, and the rule that decides
- **[Identity](../REFERENCE.md#identity)** — UUIDv7 vs natural keys, application vs database generation, `recorded_at`, `global_position`, sharding
- **[What the stream answers](../REFERENCE.md#what-the-stream-answers-that-no-single-event-does)** — and [what it won't](../REFERENCE.md#what-it-wont-answer)

## What's next

An event is a fact: *this happened*. The obvious question is what caused it — a request that could have been refused, and wasn't. That's the command, in [lab2](../lab2).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
