# Lab 9: projections

[Lab 8](../lab8) completed the minimal decision loop: read one history, fold it, decide, identify the resulting facts and conditionally append them. Which leaves an obvious problem.

*Which flavour sells best?* The events know. But answering it means folding a history, and folding a history on every page load — for every question, forever — is not a system anyone can run.

The answer is to fold *ahead of time* and keep the result. That kept result is a **projection**, or read model.

## A projection is a fold you already did

There is no new machinery here. This is `reduce` over events, exactly as in [lab6](../lab6):

```clojure
(defmethod popularity :flavour-sold
  [model event]
  (update model (get-in event [:data :flavour]) (fnil inc 0)))

(rebuild popularity log)
;; => {"vanilla" 2 "chocolate" 2}
```

Same operation, different question. What changed is who's asking:

|  | `evolve` (lab 6) | projection (here) |
|---|---|---|
| folds | one stream | the whole log |
| answers | *may this truck sell?* | *which flavour sells best?* |
| used by | `decide`, before writing | somebody reading |
| must be | current through the expected stream version | correct through its checkpoint; possibly behind the log head |

That last row is the one that matters. Eventual consistency permits **lag**, not an approximately correct fold. Given the same consumed prefix, a projection must still produce the right answer deterministically.

## Folding everything: bug in lab7, point in lab9

[Lab 7](../lab7) opened by showing that folding the entire log answers a different question — the fleet had three vanilla cones while truck 2 had one, so using the fleet total to approve an order for two from truck 2 is a bug.

Here, folding the entire log is the whole idea. Both are true, and the distinction is not about the fold:

**An aggregate's state is used to decide.** It must be exactly right at the moment of the decision, which is why it is scoped to one stream and guarded by a version check. Truck 1 cannot sell a cone because the *fleet* has one.

**A projection is usually used to look at.** A popularity chart may safely be a few seconds behind if that is its stated freshness contract. Other read models may drive workflows or customer promises, so their tolerated lag is a business decision rather than a property granted by the word “projection.”

So the rule isn't “never fold across streams.” It is: **do not enforce a strong aggregate invariant using a projection whose consistency model cannot support it.** A workflow may deliberately accept an eventually consistent input; a sale that must never take stock below zero may not. The consistency boundary from lab7 is a boundary on that command-side invariant, not a ban on cross-stream reads.

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

In this small fixture, position happens to be contiguous straight down. The contract does **not** require gapless numbers: database sequences leave gaps after rollbacks and allocation. A projector compares `position > checkpoint`; it never guesses that the next event must be `checkpoint + 1`.

Version is contiguous only after filtering to one truck. Two trucks trading at once means their events interleave, and no per-stream number can express that interleaving — which is exactly why `:stream/version` cannot do this job. There is no single version meaning "everything before here," because every stream has its own.

Like `:stream/version`, position is assigned by the store at append time. It is not the event's own property; it's the log's. `:event/id` is different: the application boundary supplies it before append and the store preserves it, as Labs [4](../lab4) and [8](../lab8) established.

And it has exactly one job — being this cursor. Position is **not** domain ordering and should never reach the domain model: ordering that carries meaning is `(:stream/id, :stream/version)`. Keeping that straight is what makes the column droppable if you ever shard the store and no single sequence exists to assign it ([REFERENCE.md](../REFERENCE.md#global_position-and-sharding) works through the options).

## Checkpoint, advance, rebuild

A persistable read model is its data *plus* the position it has consumed:

```clojure
{:state      {"vanilla" 2 "chocolate" 2}
 :checkpoint 6}
```

The projection function is runtime wiring, not stored data. `advance` receives it separately, folds whatever arrived since, and moves the mark only as far as the events actually consumed:

```clojure
(advance model popularity log)   ;; folds (since log 6), checkpoint → 7
```

Two properties fall out, and both are tested:

**Advancing with nothing new changes nothing.** That's what makes polling safe — a projection can ask repeatedly and cheaply without a special "is there anything?" path.

**Catching up incrementally equals rebuilding from scratch.** A model advanced event-by-event is indistinguishable from one folded from position 0. That equivalence is what makes the read model *disposable*: it holds nothing the events don't.

In memory, updating `:state` and `:checkpoint` means returning one new map. A durable projector must persist both in the **same transaction**. Saving state without its checkpoint re-applies an increment after a crash; saving the checkpoint without state skips an increment forever. The fold need not be intrinsically idempotent — popularity uses `inc` — because the atomic checkpoint makes each position contribute once.

Which is the payoff of the whole arrangement. A read model can be deleted and rebuilt — because the schema changed, because it was wrong, because a new question came up — provided the source events remain retained and readable and the projection depends only on reproducible inputs. A projection written today can fold facts recorded long before it existed:

```clojure
(rebuild busiest-truck log)
;; => {truck-1 2, truck-2 2}
```

That is not possible when a system retained only current state. Any past detail not represented there is gone. Event sourcing can answer a new question only when the facts needed to derive it were actually recorded; it does not recover information the model discarded.

## A caveat about position

In this lab the log is an in-memory vector, so one function can assign `max + 1` deterministically. That is a simulation, not a concurrent allocator: two writers given the same immutable log would both choose the same next position. A production store needs one authority to assign positions as part of the atomic append.

The trap is that sequence values are assigned at **INSERT** time while rows become visible at **COMMIT** time. Transaction A takes position 5; transaction B takes 6 and commits first. A projection polling `WHERE position > last_seen` sees 6, checkpoints there, and **permanently skips 5** when A commits a moment later. The failure is silent — the read model is simply missing an event, and nothing reports it.

Storing the checkpoint is right; assuming positions become *visible* in order is not. The usual fixes:

- take an advisory lock, or serialise the append, so sequence assignment and commit cannot interleave;
- track in-flight transaction ids (`pg_snapshot_xmin`) and refuse to advance the checkpoint past them;
- poll with a lag window and tolerate re-reading rows. Positions at or below the durable checkpoint are ignored; state and checkpoint must still commit atomically.

Worth settling before the first projection reaches production — [lab19](../lab19) demonstrates it against a real Postgres, and implements the second fix. Note this is *not* what the transactional outbox solves: the outbox addresses writing to the store and a broker without a dual write, which is a different problem — sketched in [lab12](../lab12) and built in [lab20](../lab20).

Unknown event semantics are another reason to stop rather than advance. Each projection names known irrelevant facts explicitly; an unrecognised event throws before a new model value or checkpoint is returned. Deploy readers that understand a new event before writers emit it, instead of silently checkpointing past data the projection may have needed.

## What's next

A projection reads the log and folds it into something you *look at*. The same machinery — read since a checkpoint, act, checkpoint again — supports a consumer that instead turns events into *commands*. That's a **policy**, in [lab10](../lab10), and it makes the system able to react to itself.

Still further out: publishing. Lab3 defined the integration message and lab4 gave it an identity, but nothing has ever published one — getting a fact out of the log and into another module exactly once, when the log and the broker are two separate things that can fail independently.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
