# Lab 4: identity

Labs [1](../lab1), [2](../lab2) and [3](../lab3) built three shapes. None of them can say *which one*. This lab gives each of them an identity, and asks where identity comes from.

## The problem

Here are lab1's events. Two vanilla ice creams were sold:

```clojure
{:event/type :flavour-sold
 :data       {:flavour :vanilla}}

{:event/type :flavour-sold
 :data       {:flavour :vanilla}}
```

These are two facts. Two customers, two cones, two sales. But they are the same value, so nothing in the program can tell them apart. `distinct` collapses them into one. A set holds one. A retry that delivers the second sale twice is indistinguishable from a genuine third sale.

An event is a historical fact, and historical facts are individual. Two sales are not one sale that happened twice. So the envelope needs a key that says *which* fact this is:

```clojure
{:event/id   #uuid "018f7a3e-0000-7000-8000-000000000001"
 :event/type :flavour-sold
 :data       {:flavour :vanilla}}
```

Now the two sales are different values, and the difference is exactly the thing that was previously missing.

### Why not derive it?

[Lab1](../lab1#identity-briefly) raises the other option: identity **derived** from the event's own properties — type, occurrence time, and the entity involved together pick out one sale, and a duplicate arrival is recognised without anyone minting anything.

That's a real technique, and this lab doesn't use it. Three reasons:

- **Collisions are silent and unfixable.** Two genuinely different sales in the same millisecond from the same truck become one event, forever. There is no repair, because the information that would distinguish them was never recorded.
- **The key is coupled to the shape.** Add a field to `:data`, or change the timestamp's precision, and what counts as "the same event" changes underneath every consumer that stored a derived key.
- **It only identifies facts.** The next section needs identity for *requests* and *deliveries* too, and neither has the properties a natural key would derive from — a retry is deliberately identical to the original.

A minted id is unconditional: it identifies this thing because it was assigned to this thing, and nothing about the event's shape can undermine it. The cost is that somebody has to mint it, which is what the second half of this lab is about.

## Three shapes, three ids

The command and the integration message need identities too — but not for the same reason, and the differences are the substance of this lab.

```clojure
;; Request: please do this
{:command/id   #uuid "018f7a3d-…-a1"
 :command/type :buy-flavour
 :data         {:flavour :vanilla}}

;; Fact: this happened
{:event/id   #uuid "018f7a3e-…-01"
 :event/type :flavour-sold
 :data       {:flavour :vanilla}}

;; Delivery: telling another module
{:message/id   #uuid "018f7a3f-…-f1"
 :message/type :flavour-sold
 :payload      {:event/id #uuid "018f7a3e-…-01"
                :flavour  :vanilla}}
```

Each id is minted by a different party, at a different moment, and answers a different question.

| Id | Identifies | Minted by | Minted when |
|---|---|---|---|
| `:command/id` | the **request** | the sender — till, app, device | before it is sent |
| `:event/id` | the **fact** | the domain | when the fact is recorded |
| `:message/id` | the **delivery** | the publisher | at each send |

The clearest way to see that these are three genuinely different things is to ask what happens when something occurs twice.

**Two identical sales → two event ids.** Same type, same data, two facts. The ids differ because the facts differ.

**One request, sent twice → one command id.** The customer taps "buy", the connection stalls, the till sends the request again. That is *one* request delivered twice, not two requests. The id is minted before the first send and reused on the retry, which is precisely what lets the truck recognise it and sell one cone rather than two. This only works because the *sender* mints the id — a receiver-generated id would be new on every arrival and could never identify a retry.

**One fact, published twice → two message ids, one event id.** A broker hiccups and the publisher sends again. New envelope, same fact.

Same-looking situation, three different answers. That's the test that an id is pulling its weight.

## But lab3 said ids belong on the message

[Lab3](../lab3) put `:message/id` on the integration message and said it belongs there rather than on the domain event, because ids are "concerns of moving a fact across a boundary, not of the fact itself."

That's still true, because `:message/id` and `:event/id` identify different things:

```text
:event/id     identifies the FACT       — one sale
:message/id   identifies the DELIVERY   — one attempt to tell someone about it
```

The relationship is one-to-many, and the consequence is practical. A consumer deduplicating on `:message/id` catches only a broker redelivering the identical envelope; a republish is genuinely a different message and slips straight through. A consumer deduplicating on the event id inside the payload catches all of it.

So the message carries both — its own delivery identity in the envelope, and the fact's identity in the payload:

```clojure
{:message/id   #uuid "…-f1"                      ; this delivery
 :message/type :flavour-sold
 :payload      {:event/id #uuid "…-01"           ; the fact
                :flavour  :vanilla}}
```

The event id crosses the boundary *inside* the payload rather than in the envelope, because from the receiving module's point of view it is data: an opaque, stable handle on someone else's fact. The envelope is about this hop.

Lab3's README is updated to say this.

## Where the id comes from

These ids are UUIDs. Which kind matters more than it looks.

**UUIDv4** is 122 random bits. Two events created a second apart are unrelated values. That is fine in memory and expensive in a database: an index over random keys has no locality, so every append lands in a different leaf page, and a store whose entire access pattern is *append at the end* spends its time scattering writes across the whole index.

**UUIDv7** puts a 48-bit millisecond timestamp in the high bits, followed by randomness:

```text
 48 bits  unix timestamp in milliseconds
  4 bits  version (7)
 12 bits  random
  2 bits  variant
 62 bits  random
```

Ids generated in time order are therefore also in *sort* order. An append only ever touches the rightmost page of the index. The id sorts, roughly, the way the log does — and it carries its own creation time, readable without a separate timestamp field.

```clojure
(uuid-v7 1700000000000 (Random. 42))
;; => #uuid "018bcfe5-6800-7af7-aee7-bbe10c45c028"
;;           └──────────┘ the millisecond, in the high 48 bits
```

An event store is the most append-heavy thing you can build, so v7 is the default here. v4 is still the right answer when an id must leak nothing — a public-facing token should not disclose when it was minted.

## Generating an id is an effect

This is the part that outlives the choice of UUID version.

```clojure
;; Don't
(defn flavour-sold [flavour]
  {:event/id   (random-uuid)          ; reaches out into the world
   :event/type :flavour-sold
   :data       {:flavour flavour}})
```

A function that calls `random-uuid` returns something different every time it is called with the same arguments. It is no longer a function of its inputs, and it cannot be asserted against:

```clojure
(is (= {:event/id ??? …} (flavour-sold :vanilla)))
```

The same is true of `(System/currentTimeMillis)`, which UUIDv7 also needs. Both are readings of the outside world, and both belong in arguments:

```clojure
(defn flavour-sold [gen-id flavour]
  {:event/id   (gen-id)
   :event/type :flavour-sold
   :data       {:flavour flavour}})
```

Production passes a real generator. A test passes whatever makes the assertion readable:

```clojure
(flavour-sold (constantly #uuid "018f7a3e-…-beef") :vanilla)
;; => {:event/id   #uuid "018f7a3e-…-beef"
;;     :event/type :flavour-sold
;;     :data       {:flavour :vanilla}}
```

`buy-flavour` and `flavour-sold-message` take the same argument, for the same reason.

The same applies one level down. `uuid-v7` takes the clock reading and the randomness as arguments rather than calling for them, so a test can seed a `java.util.Random` and get the *same id* twice:

```clojure
(= (uuid-v7 1700000000000 (Random. 42))
   (uuid-v7 1700000000000 (Random. 42)))
;; => true
```

The lesson generalises well beyond ids. Time, randomness, and identity are the three effects that quietly infect otherwise pure domain code; passing them in is what keeps `decide` — the function these labs are building toward — a function you can test by comparing two values.

It also settles a question that looks like a database detail and isn't: for an event store, the id is minted by the **application**, not by a `DEFAULT uuidv7()` in the DDL. A database-generated id doesn't exist until the write succeeds, so an ambiguous failure — timeout, dropped connection — produces a *different* id on retry, and nothing can tell the two attempts apart. An application-minted id survives the retry and lets a unique constraint make the insert idempotent.

The general form is *the writer that owns retry semantics owns the id*, which is worth holding in that shape rather than as "always the app" — it's the same reasoning that puts a global log position on the database side instead. [REFERENCE.md](../REFERENCE.md#who-generates-it--the-application-or-postgres) works through both directions.

## What's next

Every example so far — across all four labs — has been one command, one event, one message. That is the easy case, and it quietly implies the three shapes come in matched sets. They don't. [Lab5](../lab5) counts.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
