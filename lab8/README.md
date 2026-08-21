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

**It is the only function allowed to say no.** [Lab 6](../lab6) made the point from the other side — `evolve` can never refuse, because by the time an event exists the thing already happened. `decide` runs while the answer is still open. That is the whole division of labour between them.

## What decide returns, and what it doesn't

Look closely at the event above. No `:event/id`, no `:stream/id`, no `:stream/version`:

```clojure
{:event/type :flavour-sold
 :data       {:flavour "vanilla"}}
```

`decide` produces *what happened*. Where it gets recorded is not its business — the store stamps the identity, the stream, and the version when the event is appended. That's also where minting an id lives, which keeps `decide` pure exactly as [lab4](../lab4) argued: an id generator is an effect, so it belongs at the edge, and `decide` stays a function you can test by comparing two values.

The namespaces make the direction visible:

```text
lab8.truck     the domain     knows nothing about a log
lab8.store     the log        knows nothing about ice cream
lab8.handler   the loop       the only namespace that knows both
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

So `decide` throws, and the exception carries the reason:

```clojure
{:command/type :buy-flavour :flavour "pistachio" :remaining 0}
```

This is a design choice with a real alternative: return a result value — `[:ok events]` / `[:refused reason]` — rather than throwing. That composes better and makes refusal impossible to ignore, at the cost of every caller unwrapping it. Throwing is used here because it keeps the signature to one shape while the point being made is about `decide` itself. What matters is not which you pick but that **refusal is distinguishable from a no-op**.

Note also what `decide` does *not* do: emit a `:buy-flavour-refused` event. Nothing about the truck changed, so replaying such an event would have to skip it — which means it was never part of the history. Lab5 made this argument; here it's a line of code that isn't written.

## The loop

`handle` is the whole of an event-sourced write, and it is six lines:

```clojure
(defn handle [log gen-id stream-id command]
  (let [history (store/stream log stream-id)          ; 1. read
        version (store/current-version log stream-id)
        state   (truck/replay history)                ; 2. fold
        events  (truck/decide command state)]         ; 3. decide
    (store/append log stream-id version gen-id events))) ; 4. append
```

Every lab since 5 contributed one line. Read the stream ([lab7](../lab7)), fold it ([lab6](../lab6)), decide against it (here), append it on the condition that the stream hasn't moved ([lab7](../lab7)).

The version is read *before* deciding and offered back *at* the append. That gap is the whole of optimistic concurrency: two tills read version 1, both fold, both decide the sale is fine, and the second append is refused because the stream is at 2 by then. The loser's decision was made against a truck that no longer exists, so the decision is void — not the sale. It reads again, folds again, decides again, and this time gets the right answer:

```clojure
(-> log
    (handle gen-id truck-1 (buy "vanilla"))   ; till A: 2 left → sold
    (handle gen-id truck-1 (buy "vanilla")))  ; till B: 1 left → sold + depleted
```

Note the second sale correctly emits `stock-depleted`, which the stale decision would have missed entirely. Retrying is not a formality — the answer genuinely changed.

**The batch lands together.** `append` takes all of `decide`'s events and gives them consecutive versions under one version check. Lab5 established that a command's events are ordered and belong together; a store that appended them one at a time, each with its own check, could interleave another writer's event between `flavour-sold` and `stock-depleted`.

## What's next

The write side is complete: a command goes in, history comes out, and nothing is stored but events.

Everything after this is the read side and the world outside — [lab9](../lab9) turns the log into something queryable with projections, and adds the global position that lets them resume. The integration messages of [lab3](../lab3) being published to somebody comes after that.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
