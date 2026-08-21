# Lab 17: snapshots

[Lab 6](../lab6) showed that folding is resumable and named the result a snapshot without building one. [Lab 13](../lab13) called snapshots the *easy* versioning case. [Lab 16](../lab16) measured replay cost following the aggregate boundary and said the answer to a long stream was snapshots, which nothing had built.

Here it is, and the mechanism is four lines:

```clojure
(reduce evolve (:state snapshot) (events-after log stream-id (:version snapshot)))
```

So the lab is about the four ways that goes wrong.

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

That property is what makes a snapshot safe to store, safe to lose and safe to get wrong. Which makes it an unusual cache: most caches can be stale in ways you cannot detect, because the thing they cached is gone. Here the events are still there, so a snapshot can only ever be stale in ways you *can* detect — and the rest of this lab is about detecting them.

What it buys is work:

```clojure
(:folded (load-state none  log id fold))  ;; => 25
(:folded (load-state snaps log id fold))  ;; => 10
```

**Work avoided, not time saved.** These labs fold in-memory vectors; there is no latency worth reporting and inventing one would be theatre. The same choice [lab16](../lab16) made about contention.

## 2. Snapshots version by the *fold*, not the event

This is the sharp one, and the reason the lab isn't lab13 again.

Change `evolve` — add a key, rename one, start counting something else — and **every stored snapshot is wrong, although not one event changed.** The state shape moved underneath them.

So a snapshot records three things, and the third is the one people leave out:

```clojure
{:state        {:stock {"vanilla" 26} :sold 24}
 :version      15      ; how far up the stream it was folded
 :fold-version 2}      ; which shape of `evolve` produced it
```

Versioning a snapshot by *event schema version* is the common mistake. Events and folds change on different schedules for different reasons, and a snapshot is at the mercy of the second.

Trusting a stale one is silently wrong:

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

That tolerance is good practice for events and *fatal* for stale state — it means a wrong-shaped map is quietly accommodated rather than rejected. A test shows the same fold blowing up loudly on a slightly different stale shape, where `(update :sold inc)` meets a missing key.

Which is luck, not a safety net. **The fold-version check is the safety net**, and a mismatch means discard and refold:

```clojure
(:from-snapshot? (load-state stale log id fold))  ;; => false
(:folded         (load-state stale log id fold))  ;; => 25, the correct price
```

There is no upcaster for a snapshot. [Lab 13](../lab13) upcasts *events* because they are facts and cannot be recomputed. A snapshot is derived, so the answer is always to throw it away.

## 3. Read order, and the double count

Read the snapshot **first**, then the events strictly after its version. Backwards, you fold events the snapshot already contains:

```clojure
(reduce evolve state-at-v15 (whole-stream))
;; :sold is 24 + 24 — every event before the snapshot counted twice
```

Another bug that yields a number rather than an exception, which is the family this whole lab belongs to.

## 4. Taking one is off the critical path

Because the result is derived, snapshotting can fail, lag, or be skipped entirely without losing anything. So it must never sit inside the append's critical section — an aggregate that cannot be written because its *cache* refused to update has traded a real guarantee for an optimisation.

The policy is a predicate — every N events — with the trade-off stated plainly: too often is write amplification for no gain, too rarely is a long fold on every read.

## Two connections

**[Lab 9](../lab9) already built the read-side version.** A projection with a checkpoint *is* a snapshot: kept state, plus the position it was folded to, rebuilt when it's wrong. Lab 17 adds the write-side one. Same idea, two consumers — and the same disposability argument in both places.

**A snapshot can be a symptom.** If an aggregate needs one early, that may be [lab16](../lab16)'s question resurfacing: the boundary is too big, and the fix is a split rather than a cache. Worth saying, because adding a snapshot is the easier and more tempting move, and it treats the symptom while the stream keeps growing.

## What's next

A snapshot answers *what is true now*, faster. [Lab18](../lab18) asks the harder question the log has been able to answer since lab 11 and never been asked: what was true **last Tuesday** — and why that has two different right answers.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
