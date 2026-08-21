# Lab 8: decide

[Lab 2](../lab2) drew this arrow and left it unimplemented:

```text
BuyFlavour
    ↓
  decide
    ↓
FlavourSold
```

Everything since has been assembling the pieces it needs. This lab writes it, and the four steps around it close into a loop.

## The signature

```clojure
;; decide : command -> state -> [event]
(decide {:command/type :buy-flavour :data {:flavour "vanilla"}}
        {"vanilla" 3})
;; => [{:event/type :flavour-sold :data {:flavour "vanilla"}}]
```

Three things about that signature, each settled by an earlier lab.

**It takes state**, because a command cannot be judged alone. Whether this sale is allowed depends on what already happened, and [lab6](../lab6) established that "what already happened" is a fold, not a stored number.

**It returns a vector**, because [lab5](../lab5) counted: zero events, one, or several, and the order of several is significant.

**It is where context-dependent business rules may say no.** [Lab 6](../lab6) made the point from the other side: `evolve` does not re-judge a supported recorded fact, because by the time that event exists the thing already happened. `decide` runs while the business answer is still open. Boundary validation, authorisation and an optimistic-concurrency conflict may also reject work, but those are different questions at different boundaries.

## What decide returns, and what it doesn't

Look closely at the event above. No `:event/id`, no `:stream/id`, no `:stream/version`:

```clojure
{:event/type :flavour-sold
 :data       {:flavour "vanilla"}}
```

`decide` produces proposals describing *what happened*. Where they get recorded is not its business. The application handler gives each proposal an `:event/id` before append; the store preserves that identity and assigns the stream and consecutive versions it owns.

That placement keeps `decide` pure exactly as [lab4](../lab4) argued: an id generator is an effect, so it belongs at the event-recording application boundary. It also means the identified batch exists before the write. If an append result is ambiguous, the same event values — with the same ids — are the retry unit; rerunning identity allocation would describe different facts.

The namespaces make the direction visible:

```text
lab8.truck     the domain     knows nothing about a log
lab8.store     the log        knows nothing about ice cream
lab8.handler   the application boundary that knows both and identifies facts
```

`truck.clj` has no reference to `store`. That isn't tidiness for its own sake: it's what lets the same domain logic run against an in-memory vector here and a database later, and it falls out naturally once `decide` returns plain values.

## Zero events, one, several — and refusal

Lab5's counting, now produced rather than asserted:

```clojure
(decide (load-truck "vanilla" 0) {})     ;; => []            nothing happened
(decide (buy "vanilla") {"vanilla" 3})    ;; => [sold]        one fact
(decide (buy "vanilla") {"vanilla" 1})    ;; => [sold depleted]  two, in order
(decide (buy "vanilla") {"vanilla" 0})    ;; => throws        refused
```

The first and last both put nothing in the log, and they are **not the same thing**.

Loading zero cones onto the truck is a request that legitimately did nothing. Nothing happened; nothing went wrong; the caller has no problem. Buying from an empty truck also records nothing — but the customer must not be told their cone is coming. A refusal that returns `[]` is indistinguishable from success at the call site, which is how a system quietly lies to its users.

This lab chooses not to record that sold-out attempt, so `decide` throws and the exception carries the reason:

```clojure
{:command/type :buy-flavour :flavour "pistachio" :remaining 0}
```

This is a design choice with a real alternative: return a result value — `[:ok events]` / `[:refused reason]` — rather than throwing. That composes better and makes refusal impossible to ignore, at the cost of every caller unwrapping it. Throwing is used here because it keeps the signature to one shape while the point being made is about `decide` itself. What matters is not which you pick but that **refusal is distinguishable from a no-op**.

That is a local modelling choice, not a rule that refusals never belong in history. If the business cares that a purchase was refused — for fraud analysis, customer friction or a coordinating process — the refusal itself is a fact worth recording. A stock fold could handle that known event with an explicit no-op while another projection consumes it. [Lab5](../lab5) establishes the choice and [lab14](../lab14) shows a refusal that must become observable. Here, no requirement needs the fact, so no `:buy-flavour-refused` proposal is produced.

## The loop

`handle` now closes the minimal event-sourced decision loop:

```clojure
(defn handle [log gen-id stream-id command]
  (let [history (store/stream log stream-id)          ; 1. read
        version (store/current-version history stream-id)
        state   (truck/replay history)                ; 2. fold
        proposals (truck/decide command state)        ; 3. decide
        events  (identify-events gen-id proposals)]   ; identify at the edge
    (store/append log stream-id version events)))     ; 4. append
```

Every lab since 5 contributed one line. Read the stream ([lab7](../lab7)), fold it ([lab6](../lab6)), decide against it (here), append it on the condition that the stream hasn't moved ([lab7](../lab7)).

The expected version is derived from the **exact history that was folded**, then offered back at the append. That detail matters once reads use a database: reading history and current version in two independent snapshots could pair stale state with a newer version and allow a stale decision to commit.

The gap after the consistent read is where optimistic concurrency matters. Two tills read version 1, both fold, both decide the sale is fine, and the second append is refused because the stream is at 2 by then. The loser's decision was made against a truck that no longer exists, so the decision is void — not the sale. It reads again, folds again, decides again, and this time gets the right answer:

```clojure
(-> log
    (handle gen-id truck-1 (buy "vanilla"))   ; till A: 2 left → sold
    (handle gen-id truck-1 (buy "vanilla")))  ; till B: 1 left → sold + depleted
```

Note the second sale correctly emits `stock-depleted`, which the stale decision would have missed entirely. Retrying is not a formality — the answer genuinely changed.

As in Lab7, the immutable log is a deterministic model of compare-and-append rather than concurrent storage. It detects the stale version only when the second call receives the winner's updated log. A real store must make the version condition and write atomic.

**The batch must land together.** `append` takes all of `decide`'s events and gives them consecutive versions under one version check. With immutable values that is naturally all-or-nothing: either a new value is returned or the old one remains. A production adapter needs a transaction providing the same guarantee. Otherwise another writer could interleave an event between `flavour-sold` and `stock-depleted`, or only half the decision could be recorded.

The pure-core tests call `decide` and `evolve` directly as value-to-value functions. The handler tests enter through the use case with an in-memory log and a deterministic ID fake, then assert on recorded facts and resulting state. They do not mock domain internals or assert call choreography.

## What's next

The four-step decision loop is complete, not the production write side. A command can now be read against one history, decided, identified and conditionally appended as an ordered batch. Later labs add global ordering, causation, time, durable PostgreSQL enforcement, idempotency and the transactional outbox.

[Lab9](../lab9) next turns the log into something queryable with projections and adds the global position that lets them resume. The integration messages of [lab3](../lab3) being published to somebody comes after that.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
