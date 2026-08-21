# Lab 13: schema evolution

Every lab so far has assumed one schema, forever. It's the assumption that breaks first.

## You cannot migrate an event store

In a CRUD system, a shape change is a migration: rewrite the rows, and the old shape is gone. That option isn't available here. Rewriting history destroys the thing the store is *for* — [lab1](../lab1) spent a section on why an event is immutable, and "except when the schema changes" is not a footnote you can add to that.

So the old shape stays. Which means the real requirement is stronger than it first sounds:

> Your deserialiser must handle **every schema you have ever written** — not the current one.

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

## The ladder chains

One step per version, each small:

```clojure
v1 --add :price--> v2 --rename--> v3
```

`read-event` walks the chain until no step applies. You never write v1 → v3 directly, so adding a fourth version costs one function rather than revisiting three.

It also has a guard. The easiest upcaster bug to write is a step that transforms the data and forgets to raise the version — which loops forever. A bounded loop turns that into a test failure instead of a hung process, and there's a test that registers a deliberately forgetful step to prove it.

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

## Deploy readers before writers

You can upcast backwards. You cannot upcast *forwards*: a v4 event arriving at code that only knows v3 has no fix, because the function that would raise it hasn't been written yet.

So the ordering is forced. Ship the readers that understand the new version, then start writing it. In a rolling deploy that means two releases, not one.

## Keep a corpus

`corpus.clj` holds one real specimen of every shape ever written, and the suite folds all of them.

That's not a convenience fixture — it's the fitness function for *we can still read our history*. Without it, an upcaster chain rots silently: someone deletes a step that "nothing uses", and the only thing that would have noticed was a five-year-old event nobody thought to try.

The namespace carries one rule: **nothing in it may ever be edited.** Correcting a shape in the corpus is precisely the mistake the corpus exists to catch, because the events in production won't have been corrected.

## Two notes

**Snapshots are the easy case.** [Lab6](../lab6) mentioned them as an optimisation on replay and [lab17](../lab17) builds them. They're derived state in a stored shape, so they have a versioning problem too — on a different axis, and with a much better answer: delete and rebuild. Derived things never need upcasters. That's the tell for whether something is really derived.

**The published contract is the hard case.** [Lab12](../lab12) put the translation to integration messages in one file, and internal upcasting works because you own every reader. You cannot run an upcaster in someone else's process. So a breaking contract change means publishing *both* versions during a transition and retiring the old one when usage reaches zero — an operational problem, on someone else's schedule.

## Where this leaves the ladder

[REFERENCE.md](../REFERENCE.md#layer-2--envelope) lists Microsoft's four strategies in order of preference. This lab uses the first three and names the fourth:

1. **Tolerant reads** — ignore unknown fields, default missing ones. Handles additive change with no transformation at all. ([Lab22](../lab22) makes this a setting you can point at rather than a principle you have to remember.)
2. **A version identifier** — here in `[:metadata :schema-version]`. The alternative is the type name (`:flavour-sold-v2`), which has the advantage of being impossible to ignore and the cost of a type name that means two things.
3. **Upcasters** — this lab.
4. **In-place migration** — rewriting stored events. A last resort, because it breaks immutability and undermines the audit trail.

## What's next

Schema evolution keeps old events readable. It says nothing about undoing a step that already happened — which is what [lab14](../lab14) does, when the second half of a two-step process fails after the first has succeeded.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
