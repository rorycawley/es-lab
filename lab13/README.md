# Lab 13: schema evolution

Every lab so far has assumed one schema, forever. It's the assumption that breaks first.

## Do not casually rewrite an event store

In a CRUD system, a shape change commonly rewrites rows and removes the old representation. Doing that in place to an event store changes the historical record, can invalidate signatures and audit evidence, and makes replay depend on whether it happened before or after the rewrite. [Lab1](../lab1) treated recorded facts as immutable, so schema evolution starts by preserving their original bytes and adapting on read.

Physical migration is possible as a controlled last resort — for example, copying into a new store while retaining the original, recording provenance, verifying counts and hashes, and switching readers deliberately. It is not the routine answer to an application-level rename. The important rule is that an operator must never mistake a rewritten representation for the untouched fact that was originally recorded.

So the old shape stays. Which means the real requirement is stronger than it first sounds:

> A supported history reader must handle **every schema version it claims to support** — not only the version written today — and must reject versions or event semantics it does not understand.

That's the year-three problem, and nothing about it gets easier with time.

## Three changes, three different answers

`:flavour-sold` evolves across this lab, and no two changes are handled the same way.

| Change | Answer | Because |
|---|---|---|
| **v1 → v2:** add `:price` | default it, explicitly | There is no old price to recover |
| **v2 → v3:** rename `:price` → `:unit-price` | upcaster | Same fact, different encoding |
| **v3 → v4:** `:flavour` keyword → string | upcaster | Same fact, different encoding |
| `:unit-price` goes from ex-VAT to inc-VAT | **new event type** | Same encoding, different fact |

The first three are the ladder. The last is the one that matters.

## Representation versus meaning

Here is the rule the lab is built around:

> **Changes to how a fact is written down get upcast. Changes to what a fact means get a new type.**

A rename is a representation change: the old events are correct, they just spell a field differently, and a function can bridge that. When VAT starts being included, nothing is wrong with the old events either — but they are true statements about *a different quantity*. No function can bridge that, because there is nothing to fix.

What makes it dangerous is that the two look identical from the outside. Same field name, same type, same plausible range:

```clojure
{:event/type :flavour-sold       :data {:flavour :chocolate :unit-price 2.50M}}  ; ex-VAT
{:event/type :flavour-sold-gross :data {:flavour :vanilla   :unit-price 3.00M}}  ; inc-VAT
```

A test asserts those two `:data` maps have *the same key set*. Nothing structural tells them apart; only the type name does.

So the lab ships the wrong version too, and shows what it costs:

```clojure
;; the VAT change shipped as :flavour-sold v4 instead of a new type
(:net (replay [mislabelled]))   ;; => 3.00M   — a gross figure in a net total
(:net (replay [correct]))       ;; => 2.50M
```

And the failure is **silent**. Both totals are positive, both look plausible, nothing reports a problem, and the error is in every historical report from then on. That's [lab1](../lab1#name-granularity-is-irreversible)'s "granularity is irreversible" arriving at evolution: the information needed to separate the two was never recorded, so no later fix exists.

## The default for a new field is a business decision

v1 events have no price. None can be invented.

```clojure
(defmethod upcast-step [:flavour-sold 1]
  [event]
  (-> event
      (assoc-in [:data :price] :price/unknown)
      (assoc-in [:metadata :schema-version] 2)))
```

Defaulting to `0M` would be the natural technical choice and it would be a lie — every historical total would silently understate, and look right doing it. Reaching for a plausible number from an old price list would be worse, because it forges a fact.

`:price/unknown` forces every reader to decide what to do about it, which is the correct amount of friction. The fold does the honest thing with it:

```clojure
{:sold {:vanilla 3 :chocolate 1} :net 5.00M :incomplete 1}
```

The `:incomplete` count is what makes the total usable. A reader can tell the difference between "£5.00" and "£5.00 plus one sale we never priced", and that distinction is exactly what a zero default destroys.

Which is the general shape of it: **when history is missing something, propagate the gap upward rather than filling it in.**

## Upcast at the edge, on read

```text
stored event  →  read-event  →  current shape  →  evolve / decide
```

`truck.clj` contains no version numbers at all. Read it looking for one; there isn't one, and that is the entire payoff. The alternative is a `case` on schema version inside the fold — in every method, for every type, permanently, because you can never delete a branch.

Two properties keep that boundary honest, both tested:

- **Reading doesn't write back.** The stored event is unchanged after `read-event`. Writing the upgraded shape back is the last-resort migration, and it costs the audit trail.
- **Upcasting is idempotent.** Reading twice is reading once. A ladder that accumulated changes would corrupt anything that read an event more than once — which, given projections and relays, is everything.

The edge is also strict about what it claims to understand. An unregistered event type, missing or malformed schema version, future writer version, or missing ladder rung fails before the domain fold runs. Tolerant reading means accepting compatible additions to a **known** schema; it does not mean pretending unknown semantics are safe.

## The ladder chains

One step per version, each small:

```clojure
v1 --add :price--> v2 --rename--> v3
```

`read-event` walks the chain until no step applies. You never write v1 → v3 directly, so adding a fourth version costs one function rather than revisiting three.

Each rung is checked as it runs. It must advance exactly one version and preserve the fact's event id, type, occurrence time, stream id, stream version and global position. A step that forgets the version bump fails immediately rather than looping; a step that changes the recorded envelope is rejected rather than silently moving or renaming history.

## The rung that was not hypothetical

`v3 → v4` is the only step in this ladder that the repository actually needed.

[Lab19](../lab19) discovered that JSONB has no keyword type, and after curing the symptom three times the sequence adopted the rule: **do not write a keyword into a stream.** Every lab from 1 to 24 now writes `"vanilla"` rather than `:vanilla`.

Which raises the question this lab exists to answer. There are events already written with `:vanilla` in them. What happens to those?

Nothing happens to them. That is the answer.

```clojure
(defmethod upcast-step [:flavour-sold 3]
  [event]
  (-> event
      (update-in [:data :flavour] name)
      (assoc-in [:metadata :schema-version] 4)))
```

Four lines, on read, and the corpus keeps its keyword forever:

```clojure
(is (= {:flavour :vanilla} (:data corpus/flavour-sold-v1)))     ; still, and always
(is (= "vanilla" (get-in (upcast/read-event corpus/flavour-sold-v1) [:data :flavour])))
```

**Correcting the corpus was available and would have been the mistake.** It is the cheapest edit in the repository — six characters — and it would have quietly asserted that a system can reach back and unmake a decision, which is the one thing an event store guarantees you cannot. The events in production will not have been corrected.

### It also broke `current-version`

`:flavour-sold` reached v4. `:flavour-sold-gross` reached v2 by the same change. A single global version number would have to claim a v2 gross event is two versions behind, which is not a fact about anything — the two types have never shared a schema.

```clojure
(def current-version {:flavour-sold 4 :flavour-sold-gross 2})
```

**Versions are per type.** The single number worked for as long as only one type had ever changed, which is exactly how that kind of mistake survives to production.

The map is also the semantic reader's registry. A type absent from it is not silently assumed to be version 1; it is unknown and stops replay. A generic storage adapter may carry an unknown event through unchanged so another module can read it, but the consumer that folds, projects, publishes or checkpoints it must explicitly understand or deliberately ignore that type.

## Deploy readers before writers

You can upcast backwards. You cannot upcast *forwards*: a v4 event arriving at code that only knows v3 has no fix, because the function that would raise it hasn't been written yet.

So the ordering is forced. Ship every relevant reader — aggregate folds, projections, policies, relays and rebuild tools — with support for the new version, then start writing it. In a rolling deployment that is an expand-then-write sequence, often two releases. A future-version event fails loudly in this lab rather than being mistaken for current data.

## Keep a corpus

`corpus.clj` holds one real specimen of every shape ever written, and the suite folds all of them.

That's not a convenience fixture — it's the fitness function for *we can still read our history*. Without it, an upcaster chain rots silently: someone deletes a step that "nothing uses", and the only thing that would have noticed was a five-year-old event nobody thought to try.

The specimen values are append-only: add a newly written shape, but do not "correct" an old specimen to match today's model. Comments and test organization may evolve; the captured representation must continue to match the bytes or decoded value that production actually wrote. In a real system, build this corpus from anonymised production samples and keep fixtures free of secrets and personal data.

## Two notes

**Snapshots are the easy case.** [Lab6](../lab6) mentioned them as an optimisation on replay and [lab17](../lab17) builds them. They are derived state in a stored shape, so they have a versioning problem on a different axis and a safer answer: discard and rebuild from retained events. You may transform a snapshot for performance, but correctness never requires preserving it the way it requires preserving source facts.

**The published contract is the hard case.** [Lab12](../lab12) put translation in one file and noted that deriving envelopes at relay time requires historical translation behavior to remain available. Internal upcasting works because you own every reader; you cannot install an upcaster in someone else's process. A breaking contract change therefore needs an explicit strategy: publish old and new versions during a transition, introduce a new message type or endpoint, and retire the old contract only when its consumers have moved. A transactional outbox can freeze what was intended at write time, but it does not make a breaking consumer contract compatible.

## Where this leaves the ladder

[REFERENCE.md](../REFERENCE.md#layer-2--envelope) lists Microsoft's four strategies in order of preference. This lab uses the first three and names the fourth:

1. **Tolerant reads** — for a known event type and version, ignore compatible extra fields and apply deliberate defaults where the old meaning is unambiguous. This handles many additive changes without transformation; it does **not** authorize unknown event types or future schema versions. ([Lab22](../lab22) makes field openness a setting you can point at.)
2. **A version identifier** — here in `[:metadata :schema-version]`. The alternative is the type name (`:flavour-sold-v2`), which has the advantage of being impossible to ignore and the cost of a type name that means two things.
3. **Upcasters** — this lab.
4. **In-place migration** — rewriting stored events. A controlled last resort that needs retained originals, provenance and verification because it replaces the representation the store originally preserved.

## Testing the compatibility boundary

The corpus tests are behavior tests for the reader's public promise: every supported historical specimen reaches the current domain shape and folds correctly. Focused pure tests cover each ladder rung, the explicit unknown marker, per-type version targets, future-version rejection and envelope preservation. No database, mock or interaction assertion is needed because the compatibility boundary is a value transformation.

A real serializer and event-store adapter still need integration tests against the actual bytes and database types they use. Those tests prove decoding; the corpus proves historical meaning remains readable. Keep both, because a Clojure map fixture alone cannot catch a wire-format regression.

## What's next

Schema evolution keeps old events readable. It says nothing about undoing a step that already happened — which is what [lab14](../lab14) does, when the second half of a two-step process fails after the first has succeeded.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
