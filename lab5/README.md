# Lab 5: how many?

Every example across labs [1](../lab1)–[4](../lab4) has been one command, one event, one message:

```clojure
{:command/type :buy-flavour   :data    {:flavour :vanilla}}
{:event/type   :flavour-sold  :data    {:flavour :vanilla}}
{:message/type :flavour-sold  :payload {:flavour :vanilla}}
```

Three shapes in a neat column, and it is hard to read that column without concluding they come in matched sets. They don't. That tidy 1:1:1 is the easy case, not the rule, and designs that assume it break in specific ways.

This lab counts.

```text
one command  →  0..n events  →  0..n messages
```

## A command produces zero events

The request was refused. Pistachio sold out this morning, so the customer is told no.

```clojure
{:command buy-pistachio
 :events  []}
```

Nothing happened, so there is no fact to record. This is worth being precise about, because the tempting move is to record the refusal as an event — `:buy-flavour-refused` — and that is usually a mistake. Nothing about the truck changed. Rebuilding state from history would have to know to *skip* that event, which means it isn't really part of the history at all. The refusal is the caller's business, returned to them; it is the absence of an event, not a kind of event.

(There are exceptions. If the business genuinely cares that people keep asking for pistachio — enough to report on it, or reorder — then "a customer was turned away" is a fact in its own right and earns an event. The test is whether anyone downstream needs it, not whether something failed.)

## A command produces one event

The common case, and the only one the earlier labs showed.

```clojure
{:command buy-vanilla
 :events  [{:event/type :flavour-sold :data {:flavour :vanilla}}]}
```

## A command produces many events

Selling the last chocolate cone is two facts, not one:

```clojure
{:command buy-chocolate
 :events  [{:event/type :flavour-sold   :data {:flavour :chocolate}}
           {:event/type :stock-depleted :data {:flavour :chocolate}}]}
```

Both are true. Both are worth recording. Both were caused by the same request. (Whether they *should* be two events rather than one is a genuine argument, and it gets its own section below.)

This is why `decide` returns a **vector**:

```clojure
(decide command state)   ;; => [] or [event] or [event event]
```

Not an event, not a nullable event. A collection, whose empty and multi-element cases are both ordinary.

## Order is significant

A vector, specifically — not a set. When a command produces several events, the order you emit them in is part of the answer.

**The order is recorded, permanently.** The events are appended in the order given, and a stream is by definition an ordered sequence: state is rebuilt by replaying events in the order they were stored. Nothing downstream can recover the order you *meant*, only the order you wrote. So even when two events are genuinely independent, you are still choosing an order that becomes historical fact. There is no unordered batch.

**Folding is order-sensitive.** State is `(reduce evolve initial events)`, and the two orderings tell different stories:

```text
flavour-sold → stock-depleted     one cone left, sold, now none            ✓
stock-depleted → flavour-sold     none left, then a cone was sold          ✗
```

The second replays into a truck that sold a cone it didn't have. The final state might even come out the same; the history still asserts something false, and the history is the thing you are keeping.

This is explicit in the decider pattern, where `decide : Command -> State -> Event list` and each returned event is folded in turn, [passing the computed state along](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider):

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

## An event produces many messages

Two modules care that stock ran out, and they want different things:

```clojure
{:event    stock-depleted-chocolate
 :messages [{:message/type :stock-depleted       …}   ; purchasing: reorder
            {:message/type :flavour-unavailable  …}]} ; customer app: grey out the button
```

One fact, two contracts, two audiences, two envelopes — and, from [lab4](../lab4), two `:message/id` values carrying one `:event/id` between them. The consumers are free to evolve independently because neither of them is looking at the domain event.

## The fan is one-way

Every count above is a fan-**out**. The reverse never happens:

```text
one command  →  many events      ✓
many commands →  one event       ✗
one event    →  many messages    ✓
many events  →  one message      ✗
```

An event has exactly one cause. So does a message. That asymmetry is what makes history explicable: from any fact you can name the single request that produced it, and from any delivery the single fact it announces. A merged event — one caused jointly by two commands — would leave "why did this happen?" with no single answer.

## What's next

Counting the events is not the same as *deciding* them. Every `:events` vector in this lab was written by hand; what determines its contents is state — how much chocolate is left — and that means a history has to be folded into state first.

[Lab6](../lab6) folds a history into state with `evolve`. `decide` — the function that turns a command into the events this lab has been counting — follows once there is a state to decide against.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
