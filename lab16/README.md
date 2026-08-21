# Lab 16: the aggregate boundary

[Lab 7](../lab7) said a stream represents a consistency boundary and moved on. At this point the mechanics are familiar, but they do not tell you how to discover that boundary in a domain.

> A wrong aggregate boundary costs weeks; a wrong table name costs minutes.
>
> — this repository's own [`archive/04a-legal-facts/docs/04-domain-discovery.md`](../archive/04a-legal-facts/docs/04-domain-discovery.md)

This lab builds the same domain three ways and measures what separates them.

## The domain, and the invariant that spans it

Trucks draw stock from a shared depot. That gives a rule about the shared resource:

> **the depot cannot go negative**

This lab declares that a *true invariant* in Vaughn Vernon's sense: it must hold at transaction commit, not merely become true later. Notice what the wording does **not** require. The depot can decide whether its own balance covers an issue without loading every truck into the same aggregate.

A stronger rule—*the depot debit and truck credit must commit atomically, with no in-flight state*—would span both sides and lead to a different boundary or transactional design. Aggregate discovery starts by stating the actual invariant precisely.

## Three designs

```text
A   one stream: depot + every truck
B   one stream per truck; nothing owns the depot
C   one stream per truck; the depot owns its own
```

| | depot invariant | five overlapping sales | one truck decision reads |
|---|---|---|---|
| **A** | enforced; transfer atomic | **4 optimistic conflicts** | whole fleet history |
| **B** | absent from the model | 0 conflicts | one truck stream |
| **C** | enforced by depot; handoff separate | 0 conflicts | one truck stream |

Those numbers are tests, not estimates.

## Design A is not a strawman

It is the design that can debit the depot and credit the truck in one decision and one append, because one `decide` sees both sides of the movement:

```clojure
(when (< at-depot quantity)
  (throw (ex-info "Depot cannot cover that" …)))
```

If atomicity across both sides is the real rule, this boundary is justified and its contention is a cost paid deliberately. If the rule is only that the depot must never issue stock it does not hold, Design C protects it with a smaller boundary.

What it costs is a number:

```clojure
(run-concurrently design-a five-sales)
;; => {:conflicts 4}
```

Five tills, five *different* trucks, and four attempts conflict and must re-read before retrying because they share a stream and [lab7](../lab7)'s version check does its job. That is not a business refusal: after retry, the other four sales may still succeed. The design serialises every fleet write through one version.

## Design B loses something it cannot get back

Split per truck and sale conflicts vanish. In Design B the depot is not modelled at all, so neither is its invariant—there is no aggregate whose `decide` could reject an issue.

```clojure
;; five trucks each draw thirty from a depot holding a hundred
(is (= 200 on-trucks))
(is (= (+ (count design-b) 5) (count overdrawn)))  ; every append succeeded
```

No error, no conflict, no signal of any kind. Relative to the scenario's external assumption that the depot held 100, the trucks have acquired 50 cones too many. The implemented model is internally consistent because it omitted the shared stock entirely.

This is the failure mode worth internalising: a boundary drawn too small doesn't fail loudly. It quietly stops being able to ask the question.

## Design C: the smallest boundary that can decide it

The fix is not to make the aggregate bigger. It is to give the invariant an owner:

```clojure
(defmethod decide :issue-stock
  [command state]
  (when (< held quantity)
    (throw (ex-info "Depot cannot cover that" …)))
  [{:event/type :stock-issued …}])
```

The depot guards the depot. Trucks guard themselves. The test setup records a `:stock-issued` fact and then a `:truck-loaded` fact, conserving the 100 cones across both streams. A production handoff would need an explicit process ([lab11](../lab11)), idempotency and a recovery decision if the second append fails ([lab14](../lab14)); this lab does not implement that coordinator.

And the potential contention lands on the state that requires serialisation. Sales on different truck streams do not conflict. Overlapping stock issues target the depot stream:

```clojure
;; five simultaneous draws from the depot
;; => {:conflicts 4}
```

Four conflicts on the fleet stream tell you only that unrelated work shares a version. Four conflicts on the depot stream identify the shared resource. Splitting the depot by flavour or region is valid only if the invariant itself partitions on that axis; splitting for performance while retaining one global invariant would reproduce Design B's mistake.

## The rule

> **Choose the smallest consistency boundary that can decide each true invariant from its own state.**

Not the entities that feel related and not what shares a table. A consistency rule that genuinely crosses aggregate boundaries needs an explicit eventual-consistency protocol, detection and an appropriate recovery strategy. Independent facts and read-only references do not automatically need compensation.

Vernon's four rules of thumb frame the trade-off from four directions:

1. **Model true invariants in consistency boundaries** — why B is not a valid model of the stated depot rule.
2. **Design small aggregates** — why C is preferable when only the depot balance must be immediate.
3. **Reference other aggregates by identity** — stream ids address the roots; no truck embeds the depot aggregate.
4. **Use eventual consistency outside the boundary** — the protocol a complete C handoff would require.

These are rules of thumb, not a mechanical partitioning algorithm. Vernon explicitly frames one-aggregate-per-transaction as a goal for most cases and discusses reasons to break the rules. The domain's language and transactional analysis decide whether a rule is truly immediate.

## Two other criteria the numbers don't show

**Lifetime and authority.** The archive's registry splits one workflow into three aggregates — Draft, RegistrationApplication, RegisteredCompany — and not because of contention. The reason is in [`04-domain-discovery.md`](../archive/04a-legal-facts/docs/04-domain-discovery.md):

> A company does not exist because an application was approved. It exists because `RegisteredCompanyCreated` was recorded in the Register. These are causally linked but legally and structurally distinct facts.

A draft is transient and its owner may abandon it. A registered company is permanent and only the Registrar may strike it off. Different lifetimes and different authority mean different aggregates, whatever the data looks like.

**Complexity as a smell.** From [ADR-0010](../archive/04a-legal-facts/docs/adrs/0010-finite-state-machines-for-aggregates.md): *"a large FSM signals that an aggregate should be split"* — and the architecture principles put it more bluntly: **"Split the aggregate, not the state machine."** These labs have no state machines, so that one is quoted rather than measured.

## What this lab measures, and what it doesn't

Contention here is **structural, not raced**. Every writer reads the same immutable log value, then the appends are applied in turn and the store adjudicates. This deterministic schedule models maximum overlap for one batch; it does not run threads or a database.

Real conflict rates depend on transaction duration, workload, traffic shape and retry behavior, none of which this simulates. The boundary still controls which operations share a version, so holding overlap constant isolates that structural contribution. Lab 19 is where the database contract is actually raced.

It also does not measure the thing Design A is worst at over time: unrelated replay work. Its fleet stream grows with every sale by every truck. The tests show the shape—six events for a truck decision versus one. Snapshots in [lab17](../lab17) can reduce repeated folding, but they do not remove contention or repair a boundary that cannot express its invariant.

## Testing the behavior

The domain tests call each public `decide` and `replay` boundary directly to prove the depot rule, stock conservation, positive-quantity invariants and rejection of unknown semantics. The comparison tests then enter through `attempt` and `run-concurrently` with the real domain and in-memory store; injected identifiers and time are the only boundary fakes. They assert externally meaningful outcomes—recorded facts, aggregate state and optimistic-conflict counts—not internal calls.

The in-memory schedule proves the behavior of this model under fixed overlap. [Lab19](../lab19) supplies the focused adapter test against a real Postgres compare-and-append race; a small number of end-to-end tests would then prove production wiring.

## Sources

- **Vaughn Vernon**, [*Effective Aggregate Design*](https://www.dddcommunity.org/library/vernon_2011/) (2011) — the four rules of thumb.
- **This repository's archive** — [domain discovery](../archive/04a-legal-facts/docs/04-domain-discovery.md), [ADR-0010](../archive/04a-legal-facts/docs/adrs/0010-finite-state-machines-for-aggregates.md), [ADR-0017](../archive/04a-legal-facts/docs/adrs/0017-optimistic-concurrency-for-event-appending.md). ADR-0017 states the contention trade-off as a consequence; this lab turns it into a count.

## What's next

A focused boundary avoids replaying unrelated histories, although a small high-traffic aggregate can still have a long stream. [Lab17](../lab17) builds the snapshot lab 6 named and shows when caching that fold helps—and when it merely hides a boundary problem.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
