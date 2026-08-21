# Lab 14: compensation

[Lab 11](../lab11) built a two-step transfer and named the failure it didn't handle: *if the second step fails after the first succeeded, the stock is in limbo and you need to put it back.* This lab does that.

## The setup: one new thing, capacity

Trucks now have a capacity, so `load-truck` can refuse. The scenario is arranged so the process manager could not have seen it coming:

> Truck 1 has capacity 20 and is carrying 19 chocolate and 1 vanilla. It sells the vanilla → `stock-depleted`. The process asks truck 2 to unload ten vanilla; truck 2 does. The process asks truck 1 to take them — **no room**.

Ten cones have left truck 2 and are not held by either truck aggregate. The process history is the only record of the in-flight stock.

That gives the lab an invariant you can assert rather than a story you have to believe. Sum the fleet's stock: **49 before, 39 while the transfer is in flight, 49 again after compensation.**

It also reinforces [lab11](../lab11)'s constraint from the other side. Capacity is the truck's business, not the process's — the process manager asks, the aggregate refuses. A process manager that checked capacity first would be doing domain logic, and would still race.

## You cannot roll back an already committed step

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

This is not an argument against transactions. Use one local ACID transaction whenever the invariant fits inside one consistency boundary. Compensation is for a workflow whose earlier step has already committed independently, so there is no single transaction left to roll back. It restores a business effect; it does not provide the isolation or all-or-nothing guarantee of a database transaction.

## The refusal has to become a fact

This lab makes one deliberate departure from [lab8](../lab8), where `decide` throws on refusal and records nothing.

Silence supplies no fact for a process manager to fold. [Lab11](../lab11#why-the-timeout-exists-at-all) therefore needed a timer wake-up and a deadline when a donor never answered, because a refusal and a lost message look identical from outside. That works for something you're prepared to wait on. It's a poor way to learn that a step you just triggered has failed.

So `:load-refused` is recorded as an event:

```clojure
{:event/type :load-refused
 :data       {:flavour "vanilla" :quantity 10 :reason "no-room" :capacity 20 :held 19}}
```

This is [lab5](../lab5)'s exception clause with a concrete motive — *a refusal is a fact if somebody needs to know it happened*, and here somebody does. [Lab2](../lab2#a-refusal-may-itself-be-a-fact) said the same thing and now it has teeth.

The cost is real and worth stating: `:load-refused` is in the log forever, every fold that doesn't care must explicitly ignore it, and you have added an event type whose only consumer is a process manager. That's a trade, not a free win. The alternative — drive compensation from a timeout, as lab11 does for the donor — costs you a delay instead, and is the right answer for a refusal you don't control.

## Compensation can itself fail

The return is an ordinary command, so it can be refused like any other. A donor that filled up while the transfer was in flight has no room to take its own stock back.

```text
:compensating  ──stock-return-refused──▶  :needs-attention
```

Not infinite retry. The donor records `:stock-return-refused`, an aggregate-level fact rather than the workflow-level name `:compensation-failed`. The process folds that refusal into the terminal state `:needs-attention`, which a real notification adapter could surface to an operator. The truck owns whether it has room; the process owns what that refusal means for the workflow.

The test leaves ten vanilla cones outside both truck inventories and asserts it. That is the honest modeled outcome. Compensation is a business operation that can fail, not a guarantee, and a system that pretends otherwise is hiding a stuck process behind a retry loop.

## Not every step can be compensated

Three kinds of step, and the difference decides your whole design:

| Kind | Meaning | Example here |
|---|---|---|
| **compensatable** | can be semantically undone | unload from the donor → return it |
| **pivot** | the point of no return | publishing *vanilla is back* ([lab12](../lab12)) |
| **retry-only** | has no compensating action; retry or escalation is the remaining protocol | — |

Once an integration message has gone out, other modules have acted on it. You cannot untell them; you can only send a correction, which is a different event with different consequences.

The design obligation that follows: **put the pivot as late as you can.** Steps before it may have compensating actions; that makes recovery possible, not guaranteed. After the pivot, recovery requires a new forward action such as a correction. A process that pivots on step one has no compensation strategy for what follows. This transfer publishes nothing until the load has succeeded, so its one completed intermediate step has a compensating action.

*(Discussed, not implemented — the taxonomy is what changes how you design, and the code here only exercises the first row.)*

## The whole story survives

This is the payoff across independently committed consistency boundaries, where one local rollback cannot erase the earlier step.

```clojure
(map :event/type (store/correlated log conversation))
;; => [:flavour-sold :stock-depleted :flavour-unloaded :load-refused :flavour-returned]
```

A rolled-back local transaction leaves no committed domain fact in this event stream. You may still have technical telemetry, but you cannot later answer domain questions from facts that were never committed: how often transfers fail, which trucks refuse loads, or whether the fleet is sized wrongly.

Here the attempt, the partial success, the refusal, and the undo are all facts, in one conversation, across two streams. The fleet total ends where it started **and** you can see what happened to get there. A compensated process is emphatically not one that never ran.

## What this lab does not show

Dependent compensations commonly run in **reverse order** of completion — undo the last completed step first, then the one before it — because later effects may rely on earlier ones. That is a business ordering rule, not a generic stack operation: independent compensations may run in parallel, and a domain may require another order. This process has exactly one completed step to undo, so no ordering rule is exercised.

Demonstrating it would mean a second donor (ask truck 2 for six, truck 3 for four, then unwind both), which roughly doubles the machinery for one ordering rule. Saying so is better than faking it, and it's the same call [lab11](../lab11) made when it named this failure and left it for later.

## The boundaries stay strict

The process subscribes to `:stock-depleted`; a correlation id alone does not make every correlated setup or sales conversation input to this workflow. Once started, the runner re-folds only those transfer conversations and keeps polling active ones so the donor deadline can fire even after the triggering event has been checkpointed.

Both folds name every supported event explicitly and reject an unknown event type. `:load-refused` and `:stock-return-refused` are known state-neutral facts for the truck but meaningful facts for the process manager. Tolerant reading means accepting compatible fields on a known semantic contract, not silently accepting an event whose meaning this consumer does not understand.

The runner catches only the donor's named `:not-enough-to-spare` refusal. Invalid commands, unknown semantics, identifier failures and infrastructure errors remain visible instead of being mislabeled as expected business outcomes.

## Testing the behavior and the pure core

The pure truck tests exercise capacity, stock movement, return and refusal directly, with no clock, store or mocks. The pure process tests drive its public `replay` and `decide` functions through the compensation states. Runner tests then treat the in-memory log, identifier generator and explicit clock as boundary fakes and assert the use-case outcome: conversation history, fleet state, idempotency and timer wake-up. Internal call counts are irrelevant; the tests care about facts and state.

## What's next

Deletion. An event store is append-only and a person has a right to erasure, and those two facts do not get on. [Lab15](../lab15) keeps personal data out of the log where it can, encrypts per subject where it can't, and destroys the key — which leaves the fold holding data it cannot read, exactly the shape of [lab13](../lab13)'s `:price/unknown`.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
