# Lab 9: projections

[Lab 8](../lab8) finished the write side: a command goes in, history comes out, and nothing is stored but events. Which leaves an obvious problem.

*Which flavour sells best?* The events know. But answering it means folding a history, and folding a history on every page load — for every question, forever — is not a system anyone can run.

The answer is to fold *ahead of time* and keep the result. That kept result is a **projection**, or read model.

## A projection is a fold you already did

There is no new machinery here. This is `reduce` over events, exactly as in [lab6](../lab6):

```clojure
(defmethod popularity :flavour-sold
  [model event]
  (update model (get-in event [:data :flavour]) (fnil inc 0)))

(rebuild popularity log)
;; => {:vanilla 2 :chocolate 2}
```

Same operation, different question. What changed is who's asking:

|  | `evolve` (lab 6) | projection (here) |
|---|---|---|
| folds | one stream | the whole log |
| answers | *may this truck sell?* | *which flavour sells best?* |
| used by | `decide`, before writing | somebody reading |
| must be | correct at the instant of the decision | useful |

That last row is the one that matters, and the next section is about it.

## Folding everything: bug in lab7, point in lab9

[Lab 7](../lab7) opened by showing that folding the entire log gives "nobody's answer" — the fleet had two cones while truck 1 was empty, and using that number to decide a sale is a bug.

Here, folding the entire log is the whole idea. Both are true, and the distinction is not about the fold:

**An aggregate's state is used to decide.** It must be exactly right at the moment of the decision, which is why it is scoped to one stream and guarded by a version check. Truck 1 cannot sell a cone because the *fleet* has one.

**A projection is used to look at.** Nobody's invariant depends on it. If the popularity chart is a few seconds behind, the business is not harmed — nothing is being permitted or refused on the strength of it.

So the rule isn't "never fold across streams." It's **never make a decision against a projection.** The consistency boundary from lab7 is a boundary on *deciding*, not on *reading*.

This is also why `fleet-stock` in this lab re-derives something lab8's aggregate already computes. That duplication looks wrong and isn't: one exists to decide with, one exists to look at, and they are allowed to disagree by a few milliseconds. That split is what CQRS names.

## `:event/position` — the resume point

A projection that folds the whole log needs to remember where it got to, or every restart means replaying from the beginning.

[Lab 7](../lab7) deferred this key because nothing needed it yet. Now something does.

```clojure
{:event/id       #uuid "…"
 :event/position 4          ; ← where in the WHOLE log
 :stream/id      truck-2
 :stream/version 2          ;   where in THIS truck's history
 …}
```

Read the two numbers down the log:

```text
position  stream    version   event
   1      truck-1      1      truck-loaded  vanilla × 2
   2      truck-2      1      truck-loaded  chocolate × 3
   3      truck-1      2      flavour-sold  vanilla
   4      truck-2      2      flavour-sold  chocolate
   5      truck-2      3      flavour-sold  chocolate
   6      truck-1      3      flavour-sold  vanilla
```

Position is contiguous straight down. Version is contiguous only after filtering to one truck. Two trucks trading at once means their events interleave, and no per-stream number can express that interleaving — which is exactly why `:stream/version` cannot do this job. There is no single version meaning "everything before here," because every stream has its own.

Like `:stream/version`, position is assigned by the store at append time. It is not the event's own property; it's the log's.

And it has exactly one job — being this cursor. Position is **not** domain ordering and should never reach the domain model: ordering that carries meaning is `(:stream/id, :stream/version)`. Keeping that straight is what makes the column droppable if you ever shard the store and no single sequence exists to assign it ([REFERENCE.md](../REFERENCE.md#global_position-and-sharding) works through the options).

## Checkpoint, advance, rebuild

A read model is its data *plus* the position it has consumed:

```clojure
{:state      {:vanilla 2 :chocolate 2}
 :checkpoint 6}
```

`advance` folds whatever arrived since, and moves the mark:

```clojure
(advance model log)   ;; folds (since log 6), checkpoint → 7
```

Two properties fall out, and both are tested:

**Advancing with nothing new changes nothing.** That's what makes polling safe — a projection can ask repeatedly and cheaply without a special "is there anything?" path.

**Catching up incrementally equals rebuilding from scratch.** A model advanced event-by-event is indistinguishable from one folded from position 0. That equivalence is what makes the read model *disposable*: it holds nothing the events don't.

Which is the payoff of the whole arrangement. A read model can be deleted and rebuilt at will — because the schema changed, because it was wrong, because a new question came up. A projection written today folds events recorded long before it existed and knows the entire history:

```clojure
(rebuild busiest-truck log)
;; => {truck-1 2, truck-2 2}
```

That is not possible in a system that stores current state. There, the answer to a question nobody thought to ask is simply gone.

## A caveat about position

In this lab the log is an in-memory vector, so position is the index and the ordering is exact. A real store is less tidy.

The trap is that sequence values are assigned at **INSERT** time while rows become visible at **COMMIT** time. Transaction A takes position 5; transaction B takes 6 and commits first. A projection polling `WHERE position > last_seen` sees 6, checkpoints there, and **permanently skips 5** when A commits a moment later. The failure is silent — the read model is simply missing an event, and nothing reports it.

Storing the checkpoint is right; assuming positions become *visible* in order is not. The usual fixes:

- take an advisory lock, or serialise the append, so sequence assignment and commit cannot interleave;
- track in-flight transaction ids (`pg_snapshot_xmin`) and refuse to advance the checkpoint past them;
- poll with a lag window and tolerate re-delivery — which costs nothing here, because `advance` is already idempotent.

Worth settling before the first projection reaches production. Note this is *not* what the transactional outbox solves: the outbox addresses writing to the store and a broker without a dual write, which is a different problem and comes later, alongside actually publishing [lab3](../lab3)'s integration messages.

## What's next

The log now has a read side. What it doesn't have is a way to tell anybody outside.

Lab3 defined the integration message and lab4 gave it an identity, but nothing has ever published one. That means the gap above, in earnest: getting a fact out of the log and into another module exactly once, when the log and the broker are two separate things that can fail independently.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
