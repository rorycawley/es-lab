# Lab 7: streams and versions

[Lab 6](../lab6) folded an ordered collection of events into state and left one question hanging: fold **which** events?

It got away with ignoring that because there was one truck and every event belonged to it. The fleet has grown. Now the log holds both trucks' events, interleaved, and "the events" is no longer a well-defined thing.

## Folding everything gives you nobody's answer

Truck 1 loaded three vanilla cones and sold one. Truck 2 loaded two and sold one.

```clojure
(replay log)      ;; => {"vanilla" 3}
```

Five loaded, two sold, three left across the fleet. The arithmetic is right and the answer is useless for deciding what either truck may do. It isn't truck 1's stock (that's 2) and it isn't truck 2's (that's 1). It is the fleet total, which is a different question.

The damage shows up the moment you use it. *Can truck 2 fulfil an order for two vanilla cones?* The fleet total says yes — there are three. Truck 2 has one. A fold over the wrong events doesn't fail loudly; it returns a plausible number that answers a question you didn't ask.

## `:stream/id` — whose history

A **stream** is the history of one thing: one truck, one order, one account. It's the unit that gets replayed, and therefore the unit that state is derived for.

```clojure
{:event/id   #uuid "…"
 :event/type :flavour-sold
 :stream/id  #uuid "0f1c2b3a-…-0001"   ; ← this truck
 :data       {:flavour "vanilla"}}
```

Selecting a history is now a filter, and folding one is unchanged from lab6:

```clojure
(defn state-of [events stream-id]
  (replay (stream events stream-id)))

(state-of log truck-1)   ;; => {"vanilla" 2}
(state-of log truck-2)   ;; => {"vanilla" 1}
```

Note what didn't change: `evolve` and `replay` keep the shape introduced in lab6. This lab trims the example state to stock, but a fold still takes a plain sequence of events and knows nothing about streams. Stream id is about *selection*, and selection happens before folding — which is why it's a separate idea and not a change to the fold.

The Lab6 safety rule remains too: this fold has handlers for the event types it supports and fails on an unknown type. A known event that is irrelevant to this state could have an explicit no-op handler; unknown semantics must not be silently mistaken for irrelevance.

Stream id is also the **consistency boundary for this aggregate and write loop**. When lab8 adds `decide`, the rule will be: read one stream, fold it, decide against that state, append back to that same stream. One such decision does not atomically span two streams. Later workflows can coordinate several aggregates through commands and events without pretending they form one consistency boundary.

The sample history already consists of recorded facts, so every event has a fixed `:event/id`. Those identifiers are supplied as data and `append` preserves them; no event helper reaches out to randomness. That keeps the lesson aligned with [lab4](../lab4): identifier allocation is an effect owned by the event-recording application boundary, not by the pure event or fold code.

## `:stream/version` — where in that history

Version numbers each event within its own stream — 1, 2, 3, no gaps.

```clojure
{:stream/id      truck-1
 :stream/version 2
 …}
```

Three things it is *not*, each worth stating because each is a natural guess:

- **Not unique across the log.** Every truck has a version 1. The number is meaningless without a stream id beside it.
- **Not a timestamp.** It says *how many events came before this one in this stream*, not when.
- **Not the fold's business.** `evolve` never looks at it. It's a property of the stream, used at write time.

## Optimistic concurrency

Here's what version buys, and it's the reason it exists.

Two tills serve the last cone at the same instant. Both read truck 2's history, both fold it, both see one cone, and both conclude the sale is fine. Without version, both append and the truck has sold one cone more than it had.

With version, a writer offers back the version it read, *as a condition*:

```clojure
(def winner (append log truck-2 2 sale-a))
;; => new log; sale-a lands at version 3

(append winner truck-2 2 sale-b)
;; => throws: the supplied log says the stream is at 3 now
```

The second till is told the world moved. Its state is stale, so its decision is void — it has to read again, fold again, and decide again, this time against a truck with one fewer cone.

The in-memory function demonstrates the **compare-and-append contract**, not real concurrent storage. Logs here are immutable values: if both calls receive the original `log`, both can independently return a new value. The example detects the conflict because the second call receives `winner`, the value containing the first append.

A real event store must make the version condition and insert one atomic operation. In this repository Postgres eventually enforces `UNIQUE (stream_id, stream_version)` inside a transaction, so two writers cannot both claim version 3. That arrives in [lab19](../lab19); this lab establishes the condition it must enforce.

Two details define the production pattern:

**Nothing is held while the decision runs.** No lock or transaction spans read, fold and decide. The append itself is atomic: it conditionally writes only when `expected = actual`. That's what *optimistic* means here — assume no conflict while doing the work, then let the store detect one at the write boundary.

**The conflict is informative, not just a failure.** The exception carries the expected and actual versions, so the caller knows its state was stale. It must reread, refold and re-decide rather than blindly retrying the old decision.

```clojure
{:stream/id truck-2 :expected-version 2 :actual-version 3}
```

A stream that has never been written to is at version **0**, so creating one is just `expected-version = 0`. In a real store, two writers racing to create the same stream are handled by the same atomic uniqueness rule, with no special case.

## What's next

Three of the four steps of an event-sourced write now exist:

```text
1. read the stream          stream
2. fold it into state       replay          (lab 6)
3. decide                   ← missing
4. append at expected+1     append
```

Step 3 is `decide`: take a command and the state from step 2, and produce the events from step 4 — zero, one, or several of them, in order, as [lab5](../lab5) counted. That's [lab8](../lab8), and it closes the loop.

One key deliberately left out: a **global position** ordering the whole log across streams, which is what lets a read model remember where it got to and resume. There are no read models yet, so there is nothing for it to do. It arrives with the projections that need it.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
