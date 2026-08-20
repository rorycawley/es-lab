# Lab 6: evolve

Labs [1](../lab1)–[5](../lab5) built shapes. Nothing has done anything with them. This lab folds a history of events into state, which is the idea the whole repository is named after.

## State you don't store

Ask an ordinary program how many vanilla cones are left and it reads a number out of a row. That number is the truth, and the history of how it got there — if it was kept at all — is a log file nobody reads.

Event sourcing inverts it. The events are the truth. State is a *question you ask of them*:

```clojure
(replay [(truck-loaded :vanilla 3)
         (flavour-sold :vanilla)
         (flavour-sold :vanilla)])
;; => {:stock {:vanilla 1} :last-sold :vanilla}
```

Nothing stores `{:vanilla 1}`. It is recomputed whenever it's needed, and it can be thrown away without losing anything, because the events that produced it are still there.

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

## evolve never says no

Sell a flavour that was never loaded:

```clojure
(replay [(flavour-sold :pistachio)])
;; => {:stock {:pistachio -1} :last-sold :pistachio}
```

Minus one cone. That is nonsense, and `evolve` applies it without complaint.

This is deliberate, and it's the point people push back on hardest. `evolve` has no opinion about whether an event *should* have happened, because by the time an event exists, it already did — [lab2](../lab2) called this out as the whole difference between a command and an event. There's nothing left to refuse. An `evolve` that threw here would be a program that cannot read its own history, which is the one thing it must always be able to do.

Keeping the impossible out of the log is a different function's job, at a different moment: `decide`, which runs *before* the event exists and can still say no. Two responsibilities, cleanly split.

## The fold must be total

`evolve` will meet events it has no opinion about, and it has to return the state unchanged:

```clojure
(defmethod evolve :default
  [state _event]
  state)
```

Two different situations both land here.

**Facts this fold doesn't care about.** `stock-depleted` is in the history, and this fold ignores it — the count already says the stock ran out, so applying it would be double-counting. Replaying with it and without it gives the same state, which the tests assert.

**Facts this code has never heard of.** A stream outlives the code reading it. Someone adds `:truck-repainted` next year; this namespace has never seen it and must still be able to fold a history containing it. Crashing on an unknown event type means every new event type breaks every existing reader.

Neither case is exceptional. A fold has an opinion about a handful of event types and shrugs at the rest — that's normal, and it's why the default branch is a design decision rather than defensive padding.

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
(:last-sold (replay full-day))            ;; => :chocolate
(:last-sold (replay (reverse full-day)))  ;; => :vanilla
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

That property is worth noticing early because it's what makes replay affordable at scale. A stream of a million events doesn't have to be folded from zero every time — a stored intermediate state plus the events since is equivalent. That's a **snapshot**, and it's an optimisation rather than a new idea: nothing about the model changes, because the snapshot is derived and can be deleted at any time.

## What's next

`replay` takes *a* vector of events. Which raises the question this lab quietly dodged by having one truck: fold **which** events?

With a fleet, the log holds every truck's events interleaved, and folding all of them gives the stock of a truck that doesn't exist. Each history has to be identified and separated before it can be folded — that's `:stream/id`, and the version numbering that comes with it, in [lab7](../lab7).

Then `decide` in lab8, holding both the state it decides against and the version it appends with.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
