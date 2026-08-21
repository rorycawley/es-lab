# Lab 7: streams and versions

[Lab 6](../lab6) folded a vector of events into state and left one question hanging: fold **which** events?

It got away with ignoring that because there was one truck and every event belonged to it. The fleet has grown. Now the log holds both trucks' events, interleaved, and "the events" is no longer a well-defined thing.

## Folding everything gives you nobody's answer

Truck 1 loaded one vanilla cone and sold it. Truck 2 loaded three and sold one.

```clojure
(replay log)      ;; => {"vanilla" 2}
```

Four loaded, two sold, two left. The arithmetic is right and the answer is useless. It isn't truck 1's stock (that's 0) and it isn't truck 2's (that's 2). It's the fleet total, which nobody asked for.

The damage shows up the moment you use it. *Can truck 1 sell a vanilla cone?* The fleet total says yes — there are two. Truck 1 is empty. A fold over the wrong events doesn't fail loudly; it returns a plausible number that answers a question you didn't ask.

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

(state-of log truck-1)   ;; => {"vanilla" 0}
(state-of log truck-2)   ;; => {"vanilla" 2}
```

Note what didn't change: `evolve` and `replay` are exactly lab6's. A fold still takes a plain sequence of events and knows nothing about streams. Stream id is about *selection*, and selection happens before folding — which is why it's a separate idea and not a change to the fold.

Stream id is also the **consistency boundary**. When lab8 adds `decide`, the rule will be: read one stream, fold it, decide against that state, append back to that same stream. Nothing spans two. That constraint is what makes the next section possible.

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

Two tills serve the last cone at the same instant. Both read truck 2's history, both fold it, both see two cones, both conclude the sale is fine. Without version, both append and the truck has sold one cone more than it had.

With version, a writer offers back the version it read, *as a condition*:

```clojure
(append log truck-2 2 sale)   ;; => new log; the sale lands at version 3
(append log truck-2 2 sale)   ;; => throws: the stream is at 3 now
```

The second till is told the world moved. Its state is stale, so its decision is void — it has to read again, fold again, and decide again, this time against a truck with one fewer cone.

Two details make this work:

**Nothing is held between the read and the write.** No lock, no transaction spanning the decision. The check is just `expected = actual` at the moment of appending. That's what *optimistic* means: assume no conflict, detect it if you're wrong. It costs nothing when writers don't collide, which is almost always.

**The conflict is informative, not just a failure.** The exception carries the expected and actual versions, so the caller knows the stream advanced and by how much — enough to retry deliberately rather than blindly.

```clojure
{:stream/id truck-2 :expected-version 2 :actual-version 3}
```

A stream that has never been written to is at version **0**, so creating one is just `expected-version = 0` — and two writers racing to create the *same* stream are handled by exactly the same rule, with no special case.

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
