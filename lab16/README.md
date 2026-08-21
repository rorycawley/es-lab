# Lab 16: the aggregate boundary

[Lab 7](../lab7) said a stream is the consistency boundary and moved on. Fifteen labs later you know every mechanism and still don't know how to draw one in your own domain.

> A wrong aggregate boundary costs weeks; a wrong table name costs minutes.
>
> — this repository's own [`archive/04a-legal-facts/docs/04-domain-discovery.md`](../archive/04a-legal-facts/docs/04-domain-discovery.md)

This lab builds the same domain three ways and measures what separates them.

## The domain, and the invariant that spans it

Trucks draw stock from a shared depot. That gives a rule crossing them:

> **the depot cannot go negative**

Which is a *true invariant* in Vaughn Vernon's sense — a rule that must be transactionally consistent, not merely eventually true. Where you put it decides everything else.

## Three designs

```text
A   one stream: depot + every truck
B   one stream per truck; nothing owns the depot
C   one stream per truck; the depot owns its own
```

| | invariant | five concurrent sales | replay for one truck |
|---|---|---|---|
| **A** | enforced | **4 of 5 refused** | whole fleet's history |
| **B** | **unenforceable** | 0 refused | one truck's |
| **C** | enforced | 0 refused | one truck's |

Those numbers are tests, not estimates.

## Design A is not a strawman

It is the only design that can refuse an over-draw at the moment of decision, because one `decide` sees both sides of the movement:

```clojure
(when (< at-depot quantity)
  (throw (ex-info "Depot cannot cover that" …)))
```

If that rule has legal or financial force, this is the design you want, and the contention is a cost you pay deliberately.

What it costs is a number:

```clojure
(run-concurrently design-a five-sales)
;; => {:conflicts 4}
```

Five tills, five *different* trucks, and four of them are told to read again — because they share a stream and [lab7](../lab7)'s version check does its job. Nothing is wrong; the design simply serialises every write in the fleet.

## Design B loses something it cannot get back

Split per truck and the contention vanishes. So does the invariant, because **nothing owns the depot** — there is no aggregate whose `decide` could refuse.

```clojure
;; five trucks each draw thirty from a depot holding a hundred
(is (= 200 on-trucks))
(is (= (+ (count design-b) 5) (count overdrawn)))  ; every append succeeded
```

No error, no conflict, no signal of any kind. The depot is 50 cones short and the system is serenely consistent with itself, because no stream ever saw the total.

This is the failure mode worth internalising: a boundary drawn too small doesn't fail loudly. It quietly stops being able to ask the question.

## Design C: the smallest thing that owns it

The fix is not to make the aggregate bigger. It is to give the invariant an owner:

```clojure
(defmethod decide :issue-stock
  [command state]
  (when (< held quantity)
    (throw (ex-info "Depot cannot cover that" …)))
  [{:event/type :stock-issued …}])
```

The depot guards the depot. Trucks guard themselves. Moving stock between them is two facts in two streams, joined by a process ([lab11](../lab11)) with compensation if the second fails ([lab14](../lab14)) — not by a transaction.

And the contention lands somewhere meaningful. Sales contend nowhere; they never touch the depot. Restocks contend on the depot only:

```clojure
;; five simultaneous draws from the depot
;; => {:conflicts 4}
```

Which is now a **real signal rather than a mystery**. Four refusals on the fleet stream tells you nothing — everything is on the fleet stream. Four refusals on the depot tells you the depot is a hotspot, and hotspots have answers: split it by flavour, by region, by whatever the next real boundary turns out to be.

## The rule

> **The boundary is the smallest thing that owns an invariant which must be immediate.**

Not the entities that feel related. Not what shares a table. Everything outside the boundary becomes eventual consistency with detection and compensation — which this repository has already built, so lab 16 is mostly assembling parts to answer a question it hadn't asked.

Vernon's four rules of thumb say the same thing from four directions:

1. **Model true invariants in consistency boundaries** — the rule that decides A vs B.
2. **Design small aggregates** — why C beats A.
3. **Reference other aggregates by identity** — the truck holds a `:truck-id`, not a depot.
4. **Use eventual consistency outside the boundary** — labs 11 and 14, which is what C's transfer becomes.

## Two other criteria the numbers don't show

**Lifetime and authority.** The archive's registry splits one workflow into three aggregates — Draft, RegistrationApplication, RegisteredCompany — and not because of contention. The reason is in [`04-domain-discovery.md`](../archive/04a-legal-facts/docs/04-domain-discovery.md):

> A company does not exist because an application was approved. It exists because `RegisteredCompanyCreated` was recorded in the Register. These are causally linked but legally and structurally distinct facts.

A draft is transient and its owner may abandon it. A registered company is permanent and only the Registrar may strike it off. Different lifetimes and different authority mean different aggregates, whatever the data looks like.

**Complexity as a smell.** From [ADR-0010](../archive/04a-legal-facts/docs/adrs/0010-finite-state-machines-for-aggregates.md): *"a large FSM signals that an aggregate should be split"* — and the architecture principles put it more bluntly: **"Split the aggregate, not the state machine."** These labs have no state machines, so that one is quoted rather than measured.

## What this lab measures, and what it doesn't

Contention here is **structural, not raced**. Every writer reads the same log value, then the appends are applied in turn and the store adjudicates — a faithful model of optimistic concurrency, and deterministic.

Real contention also depends on transaction duration, retry policy and traffic shape, none of which this simulates. But those are tuning; the number of writers aiming at one stream is a *design decision*, and it is the one the boundary controls. That is what the lab counts.

It also does not measure the thing design A is worst at over time: replay cost. A fleet stream grows with every sale by every truck, forever. The tests show the shape of it — six events versus one — and the other answer to a long stream is snapshots, in [lab17](../lab17). Though reaching for one early is often this lab's question in disguise.

## Sources

- **Vaughn Vernon**, [*Effective Aggregate Design*](https://www.dddcommunity.org/library/vernon_2011/) (2011) — the four rules of thumb.
- **This repository's archive** — [domain discovery](../archive/04a-legal-facts/docs/04-domain-discovery.md), [ADR-0010](../archive/04a-legal-facts/docs/adrs/0010-finite-state-machines-for-aggregates.md), [ADR-0017](../archive/04a-legal-facts/docs/adrs/0017-optimistic-concurrency-for-event-appending.md). ADR-0017 states the contention trade-off as a consequence; this lab turns it into a count.

## What's next

A boundary drawn well keeps streams short, which is half of what keeps replay affordable. The other half is not folding from zero every time — [lab17](../lab17) builds the snapshot lab 6 named and never made.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
