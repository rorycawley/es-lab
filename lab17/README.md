# Lab 17: snapshots

[Lab 6](../lab6) showed that folding is resumable and named the result a snapshot without building one. [Lab 13](../lab13) called snapshots the *easy* versioning case. [Lab 16](../lab16) measured replay cost following the aggregate boundary and noted that snapshots can reduce repeated folding without repairing a poor boundary.

Here is the essential read:

```clojure
(let [events (stream log stream-id)]
  (reduce evolve (:state snapshot)
          (events-after events (:version snapshot))))
```

The optimisation is small. The lab is about the ways it can go quietly wrong.

## 1. It changes cost, never answers

The correctness condition is an equivalence, and it's the first test:

```clojure
(= (replay (stream log id))
   (fold (:state snapshot) (events-after …)))
```

With a corollary asserted directly: **delete every snapshot and no answer changes.**

```clojure
(= (:state (load-state snaps log id fold))
   (:state (load-state none  log id fold))
   truth)
```

That property makes a snapshot replaceable and safe to lose. It does **not** make a wrong snapshot harmless while it is being trusted. Fold and position metadata detect known compatibility failures; arbitrary state corruption still needs storage integrity checks, a checksum or domain-specific validation. The recovery is simple once a snapshot is rejected: discard it and replay the authoritative events.

What it buys is work:

```clojure
(:folded (load-state none  log id fold))  ;; => 25
(:folded (load-state snaps log id fold))  ;; => 10
```

**Work avoided, not time saved.** These labs fold in-memory vectors; there is no latency worth reporting and inventing one would be theatre. The same choice [lab16](../lab16) made about contention.

## 2. Snapshots version by the *fold*, not the event

This is the sharp one, and the reason the lab isn't lab13 again.

Change `evolve`—add a key, rename one, start counting something else—and stored snapshots can become incompatible although not one event changed. The state shape moved underneath them.

So a snapshot records three things, and the third is the one people leave out:

```clojure
{:state        {:stock {"vanilla" 26} :sold 24}
 :version      15      ; how far up the stream it was folded
 :fold-version 2}      ; which shape of `evolve` produced it
```

Versioning a snapshot by *event schema version* is the common mistake. Events and folds change on different schedules for different reasons, and a snapshot is at the mercy of the second.

Trusting a fold-incompatible one is silently wrong:

```clojure
;; a snapshot from when stock lived at the top level
(reduce evolve {"vanilla" 26 :sold 24} later-events)
;; => {"vanilla" 26 :sold 34 :stock {"vanilla" -10}}
```

No exception. A `:sold` count that looks entirely plausible, and a `:stock` that started again from zero while the real figure sits stranded under the old key.

### `fnil` is what hides it

Worth pausing on, because it's the mechanism of the silence. The fold is written defensively:

```clojure
(update-in state [:stock flavour] (fnil + 0) quantity)
```

That tolerance is useful where absence has a defined meaning and dangerous for stale state: it quietly accommodates a wrong-shaped map rather than rejecting it. A test shows the same fold blowing up loudly on a slightly different stale shape, where `(update :sold inc)` meets a missing key.

Which is luck, not a safety net. **The fold-version check is the compatibility safety net**, and a mismatch means discard and refold:

```clojure
(:from-snapshot? (load-state stale log id fold))  ;; => false
(:folded         (load-state stale log id fold))  ;; => 25, the correct price
```

There is normally no reason to maintain a snapshot upcaster. [Lab 13](../lab13) upcasts *events* because they are facts and cannot be recomputed. A snapshot is derived, so this lab rejects it and rebuilds.

### State and position must describe the same read

A snapshot is one coherent value: the state folded from a particular stream read and the last version in **that same read**. Reading the events and then asking the live store for its current version creates a race: an intervening append can label state through version 15 as version 16, causing the next load to skip event 16.

This implementation derives both state and version from one immutable event vector. On load it also rejects negative, malformed, or future positions. A snapshot claiming version 26 against a stream ending at 25 is not an optimisation; it is a request to skip history.

Those envelope checks still cannot prove the cached state bytes are correct. A deliberately corrupted snapshot with a valid fold version and stream version demonstrates that limit. Version metadata is a compatibility mechanism, not a checksum.

## 3. Read order, and the double count

Read the snapshot **first**, then the events strictly after its version. Backwards, you fold events the snapshot already contains:

```clojure
(reduce evolve state-at-v15 (whole-stream))
;; :sold is 24 + 24 — every event before the snapshot counted twice
```

Another bug that yields a number rather than an exception, which is the family this whole lab belongs to.

## 4. Taking one is off the critical path

Because the result is derived, snapshotting can fail, lag, or be skipped entirely without losing authoritative facts. The default design keeps it off the append's critical path: an aggregate write should not fail merely because its cache could not update. A system may deliberately write a snapshot transactionally, but then accepts the extra latency and failure coupling as an explicit trade-off.

The policy is a predicate—every N events—with the trade-off stated plainly: too often is write amplification for little gain, too rarely means more folding on reads. An incompatible snapshot is due immediately rather than waiting another N events, otherwise every intervening read would keep replaying from zero.

## Two connections

**[Lab 9](../lab9) already used the same caching pattern on the read side.** A projection and an aggregate snapshot both retain folded state plus a cursor and can be rebuilt from retained facts. They are not interchangeable: a projection answers a query across whichever events it consumes and checkpoints a global feed; an aggregate snapshot accelerates rehydration of one stream and records a stream version.

**A snapshot can hide a boundary smell, but needing one proves nothing by itself.** A small, valid aggregate may simply be busy for a long time. If its replay cost comes from unrelated histories sharing a stream, [lab16](../lab16)'s boundary question has resurfaced and a cache only treats the symptom. Split only when the true invariants, lifetime and authority permit it.

## Testing the behavior

Focused domain tests call the pure public `decide` and `replay` functions directly for invariants and strict semantics. Use-case tests enter through `application/handle`, using the in-memory log as a driven fake and fixed identity/time providers; they assert recorded facts and state, not internal calls. Snapshot tests exercise the cache as a black box and compare every accepted or rejected path with a full replay.

A production snapshot adapter still needs focused integration tests for serialization, atomic state-and-version storage, corruption handling and concurrent replacement. A few end-to-end tests should prove the composed application can recover when the snapshot store is empty or unavailable.

## What's next

A snapshot answers *what is true now* with less replay work. [Lab18](../lab18) asks a different question retained history makes possible: what was true **last Tuesday**—and why that has two different right answers.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
