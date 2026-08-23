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

## The shape has a name

`decide` and `evolve` are not two functions that happen to share a namespace. Together with the state they start from they form a **Decider**, which Jérémie Chassaing gives as four parts:

```fsharp
type Decider<'c,'e,'s> =
    { decide: 'c -> 's -> 'e list
      evolve: 's -> 'e -> 's
      initialState: 's
      isTerminal: 's -> bool }
```

Three of them are in `truck.clj`, with the argument orders matching:

| Decider | Here | Answers |
|---|---|---|
| `decide: 'c -> 's -> 'e list` | `(decide command state)` | what may happen next |
| `evolve: 's -> 'e -> 's` | `(evolve state event)` | what a fact means |
| `initialState: 's` | `(def initial-state {})` | what is true before anything happened |
| `isTerminal: 's -> bool` | — | whether there is anything more to say |

**`isTerminal` is the one missing, and its absence is a property of this domain rather than an omission to fix later.** It answers whether a state is final, so the thing can be archived and further commands refused. A truck's stock has no such state — it is loaded, sold from, and loaded again. A closed account, a completed order or a cancelled subscription does, and Chassaing's advice is to decide that up front rather than discover it when the store is full of streams nobody will ever append to again.

The value of the name is that it tells you when you have half a model. A "domain object" with rules but no fold has nothing to check them against; one with a fold but no rules is a data structure. Neither is a Decider, and neither is enough.

**And a Decider is one aggregate, not a system.** It says what may happen and what a fact means; it has nothing to say about what should happen *next*. Ismael Celis names that third step **react**, giving the trio *decide, evolve, react*:

```clojure
decide : command -> state -> [event]     ; here
evolve : state -> event -> state         ; here, from lab 6
react  : event -> [command]              ; lab 10
```

`react` is this repository's **policy** ([lab10](../lab10)), and the stateful variant that folds its own history first is the **process manager** ([lab11](../lab11)). Both return commands rather than performing effects, which keeps them as testable as `decide` — the dispatching is the application's job, and is the same choice this lab makes by having `decide` return proposals instead of writing them.

## The invariants live in `decide`

An **invariant** is a business rule that must hold whenever a change is committed — here, *the truck cannot sell a cone it does not have*. It goes in `decide`, and it is worth walking the places it cannot go, because each is somewhere people reasonably try to put it.

**Not in `evolve`.** A fold is handed facts that already happened. An `evolve` that refused an event would be claiming something did not occur after it did, and the history would then disagree with the state derived from it.

**Not at the edge as validation.** [Lab22](../lab22) draws the line, and it is a difference of inputs. Validation is a function of the request alone — *quantity must be a positive integer* needs nothing else to answer. An invariant is a function of the request **and the history**: whether this sale is allowed depends on every load and every sale before it. That is exactly why `decide` takes state and a validator does not.

**Not in the database as a constraint.** A `CHECK` sees one row. The number this rule needs — how many vanillas remain — is a fold over the whole stream, and it is not stored anywhere for a constraint to look at.

**Not in a projection.** A read model is derived and allowed to lag ([lab9](../lab9)). Deciding against one means deciding against a possibly stale answer, and nothing about a stale read stops the append that follows it.

So: `decide` is the only place with both the rule and the state the rule is about, in the same pure function, before anything is recorded.

**And the rule is only as good as the fold being current.** This is the part that is easy to miss, and [The loop](#the-loop) below is where it is paid for. `decide` checked its answer against a state folded from a particular history. If another writer appends between that fold and this append, the decision was made about a truck that no longer exists. Two tills both fold `{"vanilla" 1}`, both decide the sale is fine, both append — and the invariant held in both decisions while being false in the resulting history.

The rule lives in `decide`. Its *enforcement* is `decide` plus the version condition on the append, and neither half is sufficient alone. [Lab16](../lab16) then asks the harder question: which invariants have to be immediate at all, and what a boundary costs when it is drawn to keep one.

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

This is the enforcement half of the invariant. `decide` refuses a sale from an empty truck, and the version condition is what guarantees the truck it was told about is the truck being appended to. Take the condition away and `decide` still contains the rule, still returns the right answer for the state it was given, and the log still ends up holding two sales of one cone.

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

## Sources

- **Jérémie Chassaing**, [*Functional Event Sourcing Decider*](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider) (2021) — the four-part Decider, the insistence that `decide` stays pure and that `evolve` does only simple state application, and the argument for designing terminal states up front rather than discovering you need them.
- **Ismael Celis**, [*The Decide, Evolve, React pattern*](https://ismaelcelis.com/posts/decide-evolve-react-pattern-in-ruby/) — names the third step and, in its later form, has `react` return commands for the application to dispatch rather than perform effects itself. That is the form [lab10](../lab10) implements; the article shows the effectful version first, which is worth seeing to know what it costs in testability.
- **Vaughn Vernon**, [*Effective Aggregate Design*](https://www.dddcommunity.org/library/vernon_2011/) (2011) — true invariants as the thing that decides a consistency boundary, which is what [lab16](../lab16) measures.
- [REFERENCE.md](../REFERENCE.md#where-does-the-aggregate-boundary-go) collects the aggregate, invariant and identity material in one place.
