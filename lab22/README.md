# Lab 22: schema

[Lab 2](../lab2) drew a careful line between two kinds of rejection — **validation**, which is context-independent and belongs at the adapter "before the command object is even constructed", and **business rules**, which need state and belong in `decide`.

Then twenty labs built commands as literal maps in tests and trusted them. The left-hand column of that table has never been implemented.

This lab implements it, with Malli, under a rule taken from this repository's own archive:

```clojure
;; Schemas (used by the shell and tests only, never by cart.core)
metosin/malli {:mvn/version "0.16.4"}
```

## A schema is a boundary artefact

That comment answers the hardest question in the lab, and it isn't about purity — Malli is pure. It's that **a schema describes data crossing a line, and the core has no lines.** It has values, handed to it by an edge that already checked them.

Which is *validate at the edge, trust inside*, and that is a commitment rather than laziness. Validate in the core as well and you have said you don't trust your own boundary — then you pay for it on every internal call, forever.

[Lab 21](../lab21)'s fitness function therefore stands unchanged, with one addition:

```
core/truck.clj requires Malli — a schema is a boundary artefact
```

## Validation passing is not permission

The reason the lab exists, and one test carries it:

```clojure
(is (nil? (command/validate cmd)))                         ; well-formed
(is (thrown-with-msg? … #"Sold out" (truck/decide cmd {}))) ; and refused
```

Both are correct. They answer different questions, and the difference is whether the answer can change without the command changing:

```clojure
(command/validate cmd)          ; the same, always
(truck/decide cmd {})           ; throws
(truck/decide cmd {"vanilla" 5}) ; succeeds
```

The demo shows both, side by side:

```text
4. Two ways to say no, and they are not the same (lab 2, lab 22).
   "tarmac"  → malformed   the schema refused it; the domain never saw it
   "vanilla" → refused     well-formed, and the truck is empty
```

Same outcome — nothing recorded. Entirely different reasons, and only one of them will ever change its mind.

## The thing Malli makes it easy to get wrong

This is available, and it is a trap:

```clojure
[:fn (fn [cmd] (pos? (stock-of (:flavour cmd))))]   ; ← don't
```

The moment a business rule goes in a schema, lab 2's distinction collapses. "Is there enough vanilla?" now runs before the command exists, in a place with no access to state — so it consults something stale, or nothing at all. The schema can say `:quantity` is an integer between 1 and 500. Whether the depot can cover it is a different question, asked later, by [lab8](../lab8).

That's the same shape as [lab10](../lab10)'s causation-based dedupe: a mechanism that works right up until the case it structurally cannot see.

## Closed on the way in, open on the way out

The non-obvious part. Both directions use Malli; they use it with opposite settings.

| | direction | openness | because |
|---|---|---|---|
| **commands** | inbound, from a client | `{:closed true}` | an unexpected key is a bug or an attack |
| **events** | outbound, from your own store | open | a stream outlives its readers |

```clojure
(command/validate (assoc cmd :admin? true))     ; => rejected
(event/valid-data? :flavour-sold
                   {:flavour "vanilla" :loyalty-card "C-9"})  ; => true
```

That second one is [lab13](../lab13)'s tolerant reads, no longer a principle you have to remember but a setting you can point at. A test shows what closing the event schemas would do — reject an event carrying a field added after the code was deployed, which is precisely the reader-that-crashes-on-its-own-history lab 13 spends its length warning about.

Unknown *event types* pass through for the same reason.

## Which losses are worth a decoder

Lab 19 hit JSONB's missing keyword type and paid for it with a hand-maintained list:

```clojure
(def keyword-valued #{:flavour :reason :reason-code})   ; lab 19
```

The obvious improvement is to derive that from a schema instead — same coercion, one fewer thing to remember:

```clojure
(m/decode schema data (mt/json-transformer))            ; lab 22
```

That is what this lab used to do, and it was the right answer to the wrong question. **You do not need a decoder for a loss you can decline to have.** `:flavour` is a string now, in this lab and every lab before it, so there is nothing to restore and the schema is not asked to.

What survives is the loss JSON hands you whether you like it or not:

```clojure
[:truck-id {:optional true} :uuid]     ; a policy stamps this — lab 10
```

JSON has no UUID type. A `#uuid` goes into `data` and a string comes back, and no decision at design time avoids it. That is what `decode-data` is for now, and the distinction is worth more than the machinery:

| loss | example | fix |
|---|---|---|
| **self-inflicted** | a keyword value | do not write one |
| **inherent** | a UUID, an instant, a decimal | decode it, from the schema |

A schema-driven decoder is the right tool for the second row and the wrong tool for the first — where it works perfectly, and quietly props up a decision you should have taken differently. Same code; only one of the two uses is a good idea.

Three tests hold the line: the uuid is restored, the flavour needs no restoring, and a keyword value *would* have been lost — demonstrated directly, so the reason for the rule is visible rather than asserted.

## It completes the hexagon

Lab 21 had only **driven** adapters — store, outbox, clock. Nothing reached *in*.

`adapter/intake.clj` is a **driving** adapter, and it is where validation lives. (One of several — [lab23](../lab23) adds HTTP beside it, and the test namespaces drive it too.)

```clojure
(defn submit [deps truck-id message]
  (let [command (->command ids message)]
    (if-let [problems (schema/validate command)]
      {:rejected :malformed :because problems}
      (try {:accepted (app/handle deps truck-id command)}
           (catch ExceptionInfo e {:rejected :refused :because (ex-message e)})))))
```

Which is also why `app.clj` still contains no `if` and lab 21's fitness test still passes. Rejecting a malformed message happens *before* the application layer, so the shell only ever sees commands that are already well-formed.

A test asserts `app.clj` requires no schema namespace — validating there would be the wrong place, and now the build says so.

## Deferred

Malli's **generators**, which would give property-based tests of the core from the same schemas, and are a lab of their own. Registries, recursive schemas, and error humanisation beyond `me/humanize`.

## What's next

`intake.clj` is a driving adapter you have to call from Clojure. [Lab23](../lab23) gives it HTTP — reitit routes named after acts rather than resources, with `:malformed` and `:refused` becoming **400** and **422**, which is this lab's distinction arriving at a client.

## Running it

```bash
bb demo     # the whole system, in memory
bb test     # both adapters (Postgres half needs Docker)
```
