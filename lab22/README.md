# Lab 22: schema

[Lab 2](../lab2) drew a careful line between two kinds of rejection — **validation**, which is context-independent and belongs at the adapter before an internal command is constructed, and **business rules**, which need state and belong in `decide`.

[Lab 21](../lab21) then made the application's use cases explicit. `app/handle`, `app/stock` and `app/react` are its driving/input ports; the demo and tests already drive them. But those callers build trusted command maps inside the process. There is still no boundary for a raw message from outside it.

This lab adds that boundary. `adapter/intake.clj` is another driving adapter: it validates an untrusted message, translates it into the application's command vocabulary, and invokes the existing use-case surface. Malli remains an edge technology under a rule taken from this repository's own archive:

```clojure
;; Schemas (used by the shell and tests only, never by cart.core)
metosin/malli {:mvn/version "0.16.4"}
```

## A schema is a boundary artefact

That comment answers the hardest question in the lab, and it is not about whether Malli itself is pure. The inbound schema describes data crossing a technology boundary. The domain core describes business behaviour and invariants using plain values; it should not depend on the library chosen to parse and explain an external message.

That gives a precise sequence: **validate the external shape at the edge, construct an internal command, then let the use case and domain decide.** Repeating transport validation inside every internal call would couple the core to a concern the adapter has already discharged. This does not remove domain invariants: rules whose answer depends on current state still belong in `decide`, where every state-changing use case must pass them.

[Lab 21](../lab21)'s fitness function therefore stands unchanged, with one addition:

```
core/truck.clj requires Malli — a schema is a boundary artefact
```

## The use case stays in the centre

The schema does not become the application API and the intake adapter does not become the use case. Each has one job:

```text
untrusted message
      │
      ▼
closed schema ──▶ internal command ──▶ app/handle ──▶ truck/decide
  boundary          business intent      use case       invariants
```

The outside shape is `{:type … :data …}`. The internal shape is `{:command/id … :command/type … :data …}`. The adapter owns that translation and allocates the identifier only after the external value passes validation. The use case remains callable without Malli, HTTP or a database, and the pure core still contains the rule that decides what may happen.

This is the inward dependency rule from Lab 21 in motion: intake depends on the application; the application does not depend on intake or Malli. Replacing the schema library or adding a new delivery mechanism therefore leaves the use case and domain decisions intact.

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

The moment a state-dependent business rule goes in an inbound schema, lab 2's distinction collapses. "Is there enough vanilla?" now runs before the command exists, in a place with no authoritative state — so it consults something stale, or nothing at all. The schema can say `:quantity` is an integer between 1 and 500. Whether the depot can cover it is a different question, asked later, by [lab8](../lab8).

That's the same shape as [lab10](../lab10)'s causation-based dedupe: a mechanism that works right up until the case it structurally cannot see.

## Closed on the way in, open on the way out

The non-obvious part. Both directions use Malli; they use it with opposite settings.

| | direction | openness | because |
|---|---|---|---|
| **messages / commands** | inbound, from a client | `{:closed true}` | an unexpected key is a bug or an attack |
| **events** | outbound, from your own store | open | a stream outlives its readers |

```clojure
(command/validate-message (assoc message :admin? true)) ; => rejected
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

## A new driver, not a new centre

Lab 21's tests and demo were already **driving** adapters: they called the application functions directly. Its store, outbox and clock were **driven** adapters: the application called them. What Lab 21 lacked was a driver prepared to accept untrusted external data.

`adapter/intake.clj` supplies that driver, and it is where boundary validation lives. Notice the order:

```clojure
(defn submit [{:keys [ids] :as deps} truck-id message]
  (if-let [problems (schema/validate-message message)]
    {:rejected :malformed :because problems}
    (let [command (->command ids message)]
      (try {:accepted (app/handle deps truck-id command)}
           (catch ExceptionInfo e
             {:rejected :refused :because (ex-message e)})))))
```

The adapter validates the raw message before allocating `:command/id` or constructing the command. A test supplies an ID generator that counts calls and proves malformed input leaves that count at zero. Only then does the adapter invoke `app/handle`, where current state can turn a well-formed request into either facts or a business refusal.

`app.clj` still requires no schema namespace, so Lab 21's inward dependency rule holds. The tests also remain drivers in their own right; adding an intake adapter does not force trusted in-process callers through an external-data boundary.

Like the rings in Lab 21, this adapter is not a prescription for decomposing the whole system into `intake`, `schema`, `port` and `core` packages. It protects the boundary of this truck capability. Business capabilities still choose the larger modules; ports and adapters isolate technology within a capability where the complexity justifies them.

## Test behaviour at the boundary it belongs to

The architectural testing split is:

| Test Type | Target | Uses Fakes? | Speed & Scope |
|---|---|---|---|
| **Behavior / Use Case** | Primary ports—`app/handle`, `app/stock`, `app/react` | Yes, for secondary ports only | Fast. Covers all business logic and domain rules. |
| **Adapter / Integration** | Secondary adapters—`EventStore`, `Outbox` | No | Slower. Proves infrastructure mapping works. |
| **System / E2E** | Primary adapters such as an HTTP API | No | Very slow. A few smoke tests prove the wiring. |

`app_test.clj` enters through the use cases with in-memory store and outbox fakes. It observes facts, query state and outgoing messages, never which helper called which other helper. `adapter_test.clj` separately runs a neutral port contract against memory and real Postgres; no truck rule is repeated there.

The pure core remains directly testable. `core_test.clj` specifies invariants, replay, policies and contract mapping as input → output with no fixture or test double. Those tests supplement the public use-case suite with precise, cheap feedback; they do not assert interaction choreography.

`intake_test.clj` and `schema_test.clj` are focused tests of the new primary boundary: malformed data is stopped before an internal command exists, while valid commands may still be refused by the domain. They use driven fakes and are component tests, not System/E2E tests. Lab 22 has no HTTP primary adapter yet, so it deliberately has no E2E suite. `architecture_test.clj` is orthogonal: it enforces the selected dependency policy rather than product behaviour.

## Deferred

Malli's **generators**, which would give property-based tests of the core from the same schemas, and are a lab of their own. Registries, recursive schemas, and error humanisation beyond `me/humanize`.

## What's next

`intake.clj` accepts untrusted input but is still called as a Clojure function. [Lab23](../lab23) adds HTTP as another delivery boundary without changing the use case: reitit routes named after acts rather than resources, with `:malformed` and `:refused` becoming **400** and **422**. That is this lab's distinction arriving at a client.

## Running it

```bash
bb demo     # the whole system, in memory
bb test     # 46 tests; the Postgres adapter contract needs Docker
```
