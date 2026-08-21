# Lab 5: how many?

Every example across labs [1](../lab1)–[4](../lab4) has been one command, one event, one message:

```clojure
{:command/type :buy-flavour   :data    {:flavour "vanilla"}}
{:event/type   :flavour-sold  :data    {:flavour "vanilla"}}
{:message/type :flavour-sold  :payload {:flavour "vanilla"}}
```

Three shapes in a neat column, and it is hard to read that column without concluding they come in matched sets. They don't. That tidy 1:1:1 is the easy case, not the rule, and designs that assume it break in specific ways.

This lab counts.

```text
one command  →  0..n events  →  0..n messages
```

## A command can leave zero events

Pistachio sold out this morning, so the request is refused and the customer is told no. This lab chooses not to record that refusal:

```clojure
{:command buy-pistachio
 :events  []}
```

Zero recorded events does not by itself say *why*. A request may have been refused without creating a business fact, or it may have been an accepted idempotent no-op because the desired state was already true. The caller still needs an explicit result; an empty vector is the persistence outcome, not the whole response.

Nor does “state did not change” disqualify something from history. [Lab6](../lab6) deliberately ignores event types that do not affect its particular fold. If the business cares that customers were turned away—enough to report on it, investigate it or reorder pistachio—then `:flavour-purchase-refused` is a meaningful fact and should be recorded even if the truck's stock projection ignores it. [Lab14](../lab14) makes exactly that choice when a process needs to observe a refusal.

The rule is therefore: **record a refusal when the refusal itself is a business fact; otherwise return it to the caller without inventing history.** This example takes the second path.

## A command produces one event

The common case, and the only one the earlier labs showed.

```clojure
{:command buy-vanilla
 :events  [{:event/type :flavour-sold :data {:flavour "vanilla"}}]}
```

## A command produces many events

Selling the last chocolate cone is two facts, not one:

```clojure
{:command buy-chocolate
 :events  [{:event/type :flavour-sold   :data {:flavour "chocolate"}}
           {:event/type :stock-depleted :data {:flavour "chocolate"}}]}
```

Both are true. Both are worth recording. Both were caused by the same request. (Whether they *should* be two events rather than one is a genuine argument, and it gets its own section below.)

This is why `decide` returns a **vector of event proposals**:

```clojure
(decide command state)   ;; => [] or [event] or [event event]
```

Not an event, not a nullable event. A collection, whose empty and multi-element cases are both ordinary. These outcomes have no `:event/id`, stream or version yet; [Lab8](../lab8) puts that recording envelope around them at the store boundary.

## Order is significant

A vector, specifically — not a set. When a command produces several events, the order you emit them in is part of the answer.

**The order is recorded, permanently.** The events are appended in the order given, and a stream is by definition an ordered sequence: state is rebuilt by replaying events in the order they were stored. Nothing downstream can recover the order you *meant*, only the order you wrote. So even when two events are genuinely independent, you are still choosing an order that becomes historical fact. There is no unordered batch.

**The story is order-sensitive.** These two orderings make different historical claims:

```text
flavour-sold → stock-depleted     one cone left, sold, now none            ✓
stock-depleted → flavour-sold     none left, then a cone was sold          ✗
```

The second ordering says the flavour was already depleted and was then sold anyway. In the small fold introduced by Lab 6, `:stock-depleted` does not itself change stock, so both orders deliberately produce the same final state. That is not evidence that the order is interchangeable; it is evidence that final state alone cannot validate the story recorded by the history.

When events do affect a fold, their application is sequential. The decider pattern uses `decide : Command -> State -> Event list`, then folds each returned event in turn, [passing the computed state along](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider):

```text
state1 = evolve state0 event1
state2 = evolve state1 event2
```

The classic object-oriented aggregate reaches the same place by a different route. In [Greg Young's `SimpleCQRS`](https://github.com/jasondentler/gregyoung-simple-cqrs/blob/master/src/SimpleCQRS/Domain/AggregateRoot.cs), `ApplyChange` both records the event and applies it to the aggregate immediately, so a second event raised later in the same command is decided against state the first one already changed. Two idioms, one conclusion: within a command, events are sequential, not simultaneous.

(Worth being precise, because it's a natural guess: the functional `decide` does **not** call `evolve` internally. It stays pure and returns the list; the caller folds afterwards. Which means when a later event's content depends on an earlier one's effect, `decide` has to work that out itself rather than read it from a state that has already moved.)

**The rule that falls out:** emit events in the order the facts became true. `flavour-sold` first, because the depletion is a consequence of the sale rather than a co-occurrence.

## When many events are a smell

There is a real dissenting view here, and it's worth taking seriously rather than filing the multi-event case away as settled.

Oskar Dudycz argues for [recording a single event by default](https://event-driven.io/en/one_or_more_event_that_is_the_question/): several granular events "may be less precise than returning one bigger one," and splitting them for code reuse loses "clarity on what has happened from the business process." A related argument on the DDD/CQRS list holds that when a command's events are strongly order-*dependent*, the process boundary is probably in the wrong place, and the work wants splitting into explicit steps.

So the test for a second event isn't "did two things change?" — it's **is this a distinct business fact that something downstream needs on its own terms?**

`stock-depleted` passes that test, and the next section shows why: two modules consume it, for two different reasons, and neither of them cares about the sale. Folding it into `flavour-sold` as a `:sold-out?` flag would force both consumers to inspect a *sales* event to learn about *inventory*.

`:cash-drawer-opened` alongside every sale would fail the test. Nothing needs it separately; it's a detail of the sale.

## An event produces zero messages

Most facts are nobody else's business.

```clojure
{:event    flavour-sold-chocolate
 :messages []}
```

Publishing is a decision to expose a fact as a contract that other modules will build on, and it is made deliberately, once per event type. **The default is not to publish.** A system that mechanically turns every domain event into an integration message has no boundary left — it has published its internal model, and every future refactor is now a breaking change for someone. That is exactly the coupling [lab3](../lab3) introduced the second shape to prevent.

## An event produces one message

Opening the truck in Smithfield matters to the customer app and nobody else:

```clojure
{:event    truck-opened-smithfield
 :messages [{:message/type :truck-opened …}]}
```

One fact, one deliberately exposed contract. The fact does not need a second consumer to justify the boundary.

## An event produces many messages

Two modules care that stock ran out, and they want different things:

```clojure
{:event    stock-depleted-chocolate
 :messages [{:message/type :flavour-unavailable  …}   ; customer app: grey out the button
            {:message/type :restock-required     …}]} ; purchasing: reorder
```

One fact, two contracts and two audiences. These message proposals carry no `:message/id`; [Lab12](../lab12) makes the boundary explicit and has the publisher turn them into two transport envelopes. From [Lab4](../lab4), those envelopes receive two distinct `:message/id` values while carrying one `:event/id` between them. The consumers are free to evolve independently because neither of them is looking at the domain event.

## Cardinality is not causation

The two functions this sequence is building both fan out:

```text
decide    one command + current state  →  zero, one or many event proposals
announce  one recorded event            →  zero, one or many message proposals
```

That is a statement about these function boundaries, not a claim that software may never combine inputs. A projection can fold many events into a summary, and a process manager can observe several facts before asking for the next action. [Lab11](../lab11) exists for exactly that larger conversation.

Causation is a separate concern introduced later. At the recording boundary, every event produced by one command receives that command's id as its immediate `:causation-id` ([Lab10](../lab10)). That groups the facts from one decision, but it does not preserve the original request body; doing that requires retaining the command separately. Correlation then connects a process spanning several immediate causes. This lab counts outputs and does not pretend its nested example values are durable causation metadata.

## What's next

Counting the events is not the same as *deciding* them. Every unstamped `:events` vector in this lab was written by hand; what determines its contents is state—how much chocolate is left—and that means a history has to be folded into state first.

[Lab6](../lab6) folds a history into state with `evolve`. `decide` — the function that turns a command into the events this lab has been counting — follows once there is a state to decide against.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
