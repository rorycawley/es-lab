# Lab 6: evolve

Labs [1](../lab1)–[5](../lab5) built shapes. Nothing has done anything with them. This lab folds a history of events into state, which is the idea the whole repository is named after.

## State is derived, not authoritative

Ask an ordinary program how many vanilla cones are left and it may read a number out of a row. In that model the row is the source of record, and the history of how it got there — if it was kept at all — is often only a diagnostic log.

In this event-sourced model, the event history is the source of record. Current state is a *question you ask of it*:

```clojure
(replay [(truck-loaded load-id "vanilla" 3)
         (flavour-sold sale-1-id "vanilla")
         (flavour-sold sale-2-id "vanilla")])
;; => {:stock {"vanilla" 1} :last-sold "vanilla"}
```

Lab6 does not store `{"vanilla" 1}`. It recomputes the state from the history. Later, a snapshot may cache that derived state for speed, but the cache can be discarded and rebuilt while the event history remains the source of record.

## The shape

Two definitions, and the second is three lines:

```clojure
(def initial-state
  {:stock     {}
   :last-sold nil})

;; evolve : state -> event -> state
(defn replay [events]
  (reduce evolve initial-state events))
```

`evolve` handles exactly one event and knows nothing about histories. `reduce` supplies all the sequencing there is. That division is the whole design: the interesting part is a function of one event, and the boring part is already in the standard library.

**The initial state is part of the definition.** An empty truck is a value you choose — `{:stock {} :last-sold nil}` — not `nil` arrived at by accident. It's the answer to *what was true before anything happened*, and `(replay [])` must return it.

The event helpers take `:event/id` as an argument. Identity is assigned before an event reaches this pure core — by a caller or, later, by an adapter at the edge. Keeping UUID generation out of event construction makes equal inputs produce equal events and keeps replay deterministic without hiding a side effect.

## evolve does not re-decide recorded facts

Sell a flavour that was never loaded:

```clojure
(replay [(flavour-sold sale-id "pistachio")])
;; => {:stock {"pistachio" -1} :last-sold "pistachio"}
```

Minus one cone. That is nonsense, and `evolve` applies it without complaint.

This is deliberate. `evolve` does not decide whether a **supported recorded fact** should have happened, because by the time that event exists, it already did — [lab2](../lab2) called this out as the difference between a command and an event. Replaying `:flavour-sold` must apply the fact rather than run the business decision again.

Keeping the impossible out of the log is a different function's job, at a different moment: `decide`, which runs *before* the event exists and can still say no. Two responsibilities, cleanly split.

That does not mean every unknown event should be silently accepted. An event type whose semantics this reader does not know is a schema compatibility problem, not a business refusal.

## Handle every supported fact explicitly

Some supported events do not affect this particular state. Make that decision explicit:

```clojure
(defmethod evolve :stock-depleted
  [state _event]
  state)

(defmethod evolve :truck-repainted
  [state _event]
  state)
```

`stock-depleted` is derivable from the count, so applying it again would double-count. `truck-repainted` is a real fact but colour has no bearing on this stock projection. Replaying either event leaves this state unchanged because the projection says so deliberately.

An event type this code has never heard of is different:

```clojure
(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))
```

A blanket no-op would make an old reader appear successful while silently omitting new semantics that might affect its invariants. Instead, deploy readers that understand a new event before writers can append it, as [lab13](../lab13) develops. A reader may tolerate unknown fields, but it must not silently guess that an unknown event is irrelevant.

## Order, honestly

[Lab5](../lab5) argued the order of events matters. Folding lets us check, and the answer has a wrinkle worth keeping.

Reverse the day and the stock counts come out **identical**:

```clojure
(= (:stock (replay full-day))
   (:stock (replay (reverse full-day))))
;; => true
```

Which is not a fact about event sourcing. It's a fact about *addition*: `+3, -1, -1` lands on 1 in any order. Counters commute, and a fold made only of counters commutes with them.

Add one field that isn't a counter and it stops:

```clojure
(:last-sold (replay full-day))            ;; => "chocolate"
(:last-sold (replay (reverse full-day)))  ;; => "vanilla"
```

`:last-sold` overwrites rather than accumulates, so it remembers *which event came last*. The two histories are now different states.

The lesson isn't "folds are order-sensitive" — it's that **you cannot rely on them not being.** Almost any state richer than a tally (a status, a latest value, a flag that latches, anything derived from a transition rather than a total) is order-dependent, and the commuting case is the exception you happened to get for free. Lab5's stronger point still stands regardless: even where the arithmetic agrees, a history that says the cone was sold before the truck was loaded is asserting something false, and the history is the thing being kept.

## Folding is resumable

`reduce` doesn't insist on starting from `initial-state`:

```clojure
(= (replay full-day)
   (reduce evolve (replay morning) afternoon))
;; => true
```

Fold the morning, keep the result, carry on with the afternoon. Same answer.

That property is worth noticing early because it's what makes replay affordable at scale. A stream of a million events doesn't have to be folded from zero every time — a stored intermediate state plus the events since is equivalent. That's a **snapshot** ([lab17](../lab17)), and it's an optimisation rather than a new source of record: the snapshot is derived and can be deleted and rebuilt.

## What's next

`replay` takes an ordered collection of events. Which raises the question this lab quietly dodged by having one truck: fold **which** events?

With a fleet, the log holds every truck's events interleaved, and folding all of them gives the stock of a truck that doesn't exist. Each history has to be identified and separated before it can be folded — that's `:stream/id`, and the version numbering that comes with it, in [lab7](../lab7).

Then `decide` arrives in lab8 and takes the command and current state. The handler surrounding it carries the expected version from the read into the append.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
