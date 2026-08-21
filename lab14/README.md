# Lab 14: compensation

[Lab 11](../lab11) built a two-step transfer and named the failure it didn't handle: *if the second step fails after the first succeeded, the stock is in limbo and you need to put it back.* This lab does that.

## The setup: one new thing, capacity

Trucks now have a capacity, so `load-truck` can refuse. The scenario is arranged so the process manager could not have seen it coming:

> Truck 1 has capacity 20 and is carrying 19 chocolate and 1 vanilla. It sells the vanilla → `stock-depleted`. The process asks truck 2 to unload ten vanilla; truck 2 does. The process asks truck 1 to take them — **no room**.

Ten cones have left truck 2 and arrived nowhere.

That gives the lab an invariant you can assert rather than a story you have to believe. Sum the fleet's stock: **49 before, 39 while the transfer is in flight, 49 again after compensation.**

It also reinforces [lab11](../lab11)'s constraint from the other side. Capacity is the truck's business, not the process's — the process manager asks, the aggregate refuses. A process manager that checked capacity first would be doing domain logic, and would still race.

## You cannot roll back

`:flavour-unloaded` happened. There is no undo for it, because [lab1](../lab1) is not negotiable: an event is a fact, and facts don't un-happen.

What exists instead is a **compensating action** — a further business action whose effect is opposite. Not an erasure; an entry on the other side. It's [lab1's reversal](../lab1#immutable-at-a-real-cost) at the scale of a process rather than a single mistake.

## A compensating action is a business action

The undo is not `(reverse event)`. It's a command, decided by an aggregate, producing its own event:

```clojure
:return-stock  →  :flavour-returned
```

Not `:truck-loaded`. A test pins the donor's stream as:

```text
:truck-commissioned  :truck-loaded  :flavour-unloaded  :flavour-returned
```

If the return had reused `:truck-loaded`, the arithmetic would be right and the history would be a lie — indistinguishable from the depot delivering ten more cones. That is [lab1's granularity argument](../lab1#name-granularity-is-irreversible) again: a movement and an undo of a movement are different facts, and if you record them with one name the difference is gone permanently.

There is no generic undo, and reaching for one is the tell that the pattern has been misread.

## The refusal has to become a fact

This lab makes one deliberate departure from [lab8](../lab8), where `decide` throws on refusal and records nothing.

A process manager cannot observe silence. [Lab11](../lab11#why-the-timeout-exists-at-all) needed a *timeout* just to notice a donor never answered, because a refusal and a lost message look identical from outside. That works for something you're waiting on indefinitely. It's a poor way to learn that a step you just triggered has failed.

So `:load-refused` is recorded as an event:

```clojure
{:event/type :load-refused
 :data       {:flavour "vanilla" :quantity 10 :reason "no-room" :capacity 20 :held 19}}
```

This is [lab5](../lab5)'s exception clause with a concrete motive — *a refusal is a fact if somebody needs to know it happened*, and here somebody does. [Lab2](../lab2#a-refusal-may-itself-be-a-fact) said the same thing and now it has teeth.

The cost is real and worth stating: `:load-refused` is in the log forever, every fold that doesn't care must ignore it, and you have added an event type whose only consumer is a process manager. That's a trade, not a free win. The alternative — drive compensation from a timeout, as lab11 does for the donor — costs you a delay instead, and is the right answer for a refusal you don't control.

## Compensation can itself fail

The return is an ordinary command, so it can be refused like any other. A donor that filled up while the transfer was in flight has no room to take its own stock back.

```text
:compensating  ──return refused──▶  :needs-attention
```

Not infinite retry. A terminal state, recorded as `:compensation-failed`, that a human is told about — for exactly the reason [lab11](../lab11) gave for recording the give-up: **the stopping has to be a fact**, or the process retries forever.

The test leaves ten vanilla cones genuinely missing and asserts it. That is the honest outcome. Compensation is a best effort, not a guarantee, and a system that pretends otherwise is hiding a stuck process behind a retry loop.

## Not every step can be compensated

Three kinds of step, and the difference decides your whole design:

| Kind | Meaning | Example here |
|---|---|---|
| **compensatable** | can be semantically undone | unload from the donor → return it |
| **pivot** | the point of no return | publishing *vanilla is back* ([lab12](../lab12)) |
| **retry-only** | must eventually succeed | — |

Once an integration message has gone out, other modules have acted on it. You cannot untell them; you can only send a correction, which is a different event with different consequences.

The design obligation that follows: **put the pivot as late as you can.** Every step before it is recoverable; everything after it is not. A process that pivots on step one has no compensation strategy, only optimism. This transfer publishes nothing until the load has succeeded, which is why the whole of it is compensatable.

*(Discussed, not implemented — the taxonomy is what changes how you design, and the code here only exercises the first row.)*

## The whole story survives

This is the payoff, and it's the reason to prefer this over a distributed transaction.

```clojure
(map :event/type (store/correlated log conversation))
;; => [:flavour-sold :stock-depleted :flavour-unloaded :load-refused :flavour-returned]
```

A database rollback leaves *no trace*. You cannot later ask how often transfers fail, which trucks refuse loads, or whether the fleet is sized wrongly — the evidence was discarded as part of the mechanism.

Here the attempt, the partial success, the refusal, and the undo are all facts, in one conversation, across two streams. The fleet total ends where it started **and** you can see what happened to get there. A compensated process is emphatically not one that never ran.

## What this lab does not show

Compensation runs in **reverse order** of completion — undo the last completed step first, then the one before it. This process has exactly one completed step to undo, so the rule gets stated and not exercised.

Demonstrating it would mean a second donor (ask truck 2 for six, truck 3 for four, then unwind both), which roughly doubles the machinery for one ordering rule. Saying so is better than faking it, and it's the same call [lab11](../lab11) made when it named this failure and left it for later.

## What's next

Deletion. An event store is append-only and a person has a right to erasure, and those two facts do not get on. [Lab15](../lab15) keeps personal data out of the log where it can, encrypts per subject where it can't, and destroys the key — which leaves the fold holding data it cannot read, exactly the shape of [lab13](../lab13)'s `:price/unknown`.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
