# Decide — business rules as data

This repository is a deliberately small Clojure experiment around one architectural idea:

```text
valid state + valid command + semantic bundle -> business decision
```

The business rules for a domain are **EDN data**. A small, generic, pure Clojure interpreter gives that data computational meaning.

The repository is intentionally focused on **`decide` only**. It does not attempt to be an event-sourcing framework, workflow engine, rules platform, application framework, or infrastructure abstraction.

This README is written both for a human reader and for an AI coding agent such as Codex. It documents not only what the code does, but the reasoning behind the design, the semantic contracts that must be preserved, and the boundaries that should not be crossed accidentally.

---

## 1. The idea in one sentence

For a state-changing business request, put the facts needed to make the decision into immutable data, express the business rules as immutable EDN data, and let a pure interpreter return immutable data describing the business decision.

Conceptually:

```text
                         semantic bundle
                              |
                              v
valid state + valid command -> decide -> decision
```

For a particular domain, the semantic bundle is fixed, so the useful mental model remains:

```text
State x Command -> Decision
```

The bundle is the executable definition of what that domain-specific `decide` means.

---

## 2. Why this exists

The motivating problem is not that business domains are simple. They are not, and this design does not try to make them simple.

The motivating problem is that business logic in large information systems tends to become difficult to locate. Rules are easily scattered across controllers, services, database queries, UI code, validators, message consumers, orchestration code, and framework callbacks.

The experiment asks:

> Can the business decision for a state-changing request be made explicit, pure, inspectable, testable, and locatable?

The answer explored here is:

```text
state + command + explicitly versioned business semantics -> decision
```

The important benefits are:

- the domain decision is isolated from infrastructure;
- the complete decision can be exercised in a REPL using ordinary data;
- business rules are visible and inspectable as EDN;
- each rule has a stable business-rule identifier and human-readable text;
- structural validity is kept separate from business acceptability;
- repeated domain calculations can be named explicitly;
- rule ordering is part of the semantics rather than an implementation accident;
- the exact semantic bundle used for a decision can be identified by human version and content hash;
- the interpreter is reusable across unrelated domains without learning their terminology;
- Malli can validate and generate the data around the decision model;
- most of the interesting code remains pure and therefore cheap to test.

This is primarily an experiment in **semantic software design**: preserve business meaning as directly as possible and push software machinery away from that meaning.

---

## 3. What this repository is deliberately NOT

This scope is important. Do not expand it casually.

There is deliberately no:

- `evolve`;
- `react`;
- aggregate implementation;
- process manager;
- policy engine beyond the rule interpretation shown here;
- event store;
- event replay;
- database;
- HTTP server;
- command bus;
- message bus;
- inbox/outbox;
- persistence;
- concurrency control;
- optimistic locking;
- projection system;
- effect execution;
- dependency injection framework;
- macro-based DSL;
- `eval`;
- general-purpose programming language hidden inside EDN.

Those may all be useful elsewhere. They are excluded here so that the repository can answer one question clearly:

> What does a pure, data-driven `decide` look like across several very different business domains?

The events returned by accepted decisions do **not** make this project event sourced. They are simply immutable data describing the accepted business outcome. A future application could persist them in an event store, translate them into another state representation, or use them in some other way. None of those choices belong to this repository.

---

## 4. Core architectural boundary

The system separates three concerns that are often conflated:

```text
+--------------------------+
| 1. Structural validity   |
| Malli                    |
+------------+-------------+
             |
             v
+--------------------------+
| 2. Business decision     |
| semantic bundle +        |
| pure DSL interpreter     |
+------------+-------------+
             |
             v
+--------------------------+
| 3. Operational effects   |
| OUT OF SCOPE HERE        |
+--------------------------+
```

This is intentionally compatible with the `pull -> transform -> push` framing in *Elements of Clojure*:

- resource loading is an operational edge;
- validation and decision-making work on data;
- the DSL interpreter is a pure transform;
- the output is still data;
- effectful interpretation of that output belongs somewhere else.

The repository should continue to keep the functional middle large and the operational edge small.

---

## 5. Project layout

```text
decide-rules-data/
├── README.md
│
├── deps.edn            ; dependencies and aliases
├── bb.edn              ; the tasks below
├── mise.toml           ; pinned toolchain
├── tests.edn           ; Kaocha configuration
├── cljfmt.edn          ; one indent rule, for prop/for-all
├── .clj-kondo/         ; lint configuration, including Malli's exported config
│
├── src/
│   └── decider/
│       ├── bundle.clj
│       ├── core.clj
│       ├── dsl.clj
│       ├── hash.clj
│       ├── identity.clj
│       └── schema.clj
│
├── dev/
│   └── decider/
│       └── playground.clj
│
├── test/
│   ├── resources/           ; examples and malformed fixtures, test-only
│   │   ├── semantic-bundles/
│   │   └── bundle-fixtures/
│   └── decider/
│       ├── fixtures.clj
│       ├── bundle_test.clj
│       ├── identity_test.clj
│       ├── interpreter_test.clj
│       ├── validation_test.clj
│       └── generative_test.clj
│
└── consumer-test/           ; isolated external-consumer contract check
    ├── deps.edn
    ├── resources/
    └── src/
```

The dependencies are intentionally small:

```text
Clojure 1.12.5
Malli 0.20.1
org.clojure/test.check 1.1.3   ; dev/test only
lambdaisland/kaocha 1.91.1392  ; test runner only
```

There is no database, no server, no Docker, and no build step. Every dependency
is on the classpath of a pure function.

### Running it

The toolchain is pinned in `mise.toml`, so the first command installs the exact
Java, Clojure, babashka, clj-kondo and cljfmt this project was written against:

```bash
mise trust && mise install   # or: bb setup, once babashka is present
```

Then:

```bash
bb test        # run the suite
bb check       # clj-kondo and cljfmt, both must be silent
bb consumer:smoke # exercise the external consumer contract
bb verify      # check, test, and consumer smoke test
bb dev         # JVM REPL with dev/ and test/ on the classpath
bb all         # setup, then verify
bb tasks       # everything available
```

Each of these is a thin wrapper over the underlying command, printed with a
leading `+` before it runs, so nothing is hidden and any of them can be typed
directly instead.

### Production support contract

This project is supported as an **internal source library**, not as a published
Maven or Clojars artifact. A sibling project can depend on it locally:

```clojure
{decide-rules-data/decide-rules-data
 {:local/root "../decide-rules-data"}}
```

A consumer outside this checkout should use a Git dependency pinned to a full,
immutable commit SHA rather than a branch:

```clojure
{io.github.rorycawley/decide-rules-data
 {:git/url "https://github.com/rorycawley/es-lab.git"
  :git/sha "<full-commit-sha>"
  :deps/root "decide-rules-data"}}
```

The supported runtime matrix is Java 21 and Java 25. CI exercises both. The
supported compatibility surface is:

- `decider.core/prepare`, `prepared?`, `specification`, `decide`, and
  `prepare-and-decide`;
- `decider.bundle/load` and `load-prepared`;
- `decider.schema/problems`, `assert-valid-bundle!`, the documented schema
  values, and the documented DSL/result data shapes.

Other public vars and the implementation namespaces `decider.dsl`,
`decider.hash`, and `decider.identity` are inspectable but are not independent
compatibility promises. Change the supported data shapes and functions only as
an intentional internal API revision.

Semantic bundles are **trusted, code-reviewed application artifacts**. This
library validates their structure and bounds the work performed after parsing;
it does not claim hostile-input isolation. Do not accept arbitrary user-uploaded
EDN as a bundle. Load and prepare each reviewed bundle during application
startup, fail startup if preparation throws, and reuse the opaque prepared value
for requests.

The consuming application owns coherent state, concurrency control, persistence,
effects, exception reporting, logging, metrics, and deployment. A `:spec/hash`
is content identity, not proof of authorship or approval.

The seven branded bundles are executable documentation and test fixtures. They
live under `test/resources`, are available to `bb test` and `bb dev`, and are
deliberately absent from a consumer's production classpath. Applications supply
their own reviewed resources; `consumer-test` proves that boundary.

Dependency scanning is separate from the offline verification suite:

```bash
GITHUB_TOKEN=... bb vuln:github
CLJ_WATSON_NVD_API_KEY=... bb vuln:nvd
```

CI runs the GitHub advisory check for changes and the NVD check on a schedule.
The NVD API key belongs in the repository secret
`CLJ_WATSON_NVD_API_KEY`; never commit it.

---

# Part I — the decision model

## 6. There are two levels of `decide`

It is useful to distinguish the conceptual domain function from the public implementation in this repository.

### Conceptual domain function

Once a semantic bundle is fixed for a particular command:

```text
State x Command -> Decision
```

For example:

```text
AuctionState x PlaceBid -> BidDecision
```

### Generic data-driven implementation

The generic interpreter has one additional explicit input:

```text
Specification x State x Command -> Decision
```

In Clojure, the public entry point is:

```clojure
(decider/decide (decider/prepare specification) state command)
```

or, for a single decision where the preparation cost does not matter:

```clojure
(decider/prepare-and-decide specification state command)
```

`decide` takes a *prepared* specification and refuses a plain bundle. See
section 43 for why the split exists and why the convenience path is the one
with the longer name.

The specification is not really an extra business fact. It is the executable definition of the domain's decision semantics.

Conceptually, fixing the specification gives us the domain-specific function:

```clojure
(partial decider/decide (decider/prepare specification))
```

which again behaves like:

```text
State x Command -> Result
```

`prepare` is what makes that partial application worth doing rather than merely
tidy: it validates, hashes and compiles the bundle once, so the fixed
specification really is fixed rather than re-derived on every request. See
section 43.

---

## 7. Commands are data describing intent

A command is not inherently a class, queue message, API request, or infrastructure concept.

In this repository it is ordinary immutable Clojure data describing a request to change something.

Example:

```clojure
{:command/type :reserve-tickets
 :data {:customer-id "customer-1"
        :quantity 2}}
```

The command does not perform anything.

It asks the domain to decide whether something may happen.

---

## 8. State is data containing the facts needed for the decision

The interpreter does not load data.

It does not query a database.

It does not call another service.

Whatever facts are needed by the business decision must already be present in the supplied state and command.

Example:

```clojure
{:performance-id "oasis-dublin-2026"
 :sale-status :open
 :tickets-remaining 100
 :max-tickets-per-customer 4
 :customer-id->tickets-reserved
 {"customer-1" 2}}
```

This is a critical purity boundary.

Do **not** add functions such as these to the DSL interpreter:

```clojure
(db/find-customer ...)
(http/get ...)
(clock/now)
(random-uuid)
```

If a decision genuinely needs a fact, acquire it outside the pure decision boundary and supply the fact as data.

---

## 9. A decision is data describing the business answer

A valid request can produce one of two business outcomes.

### Rejected

```clojure
{:decision/type :rejected
 :spec/ref {:id :ticketmaster/reserve-tickets
            :version 1
            :hash "sha256:..."}
 :rule/id :BR-4
 :reason :ticket-limit-exceeded}
```

### Accepted

```clojure
{:decision/type :accepted
 :spec/ref {:id :ticketmaster/reserve-tickets
            :version 1
            :hash "sha256:..."}
 :events
 [{:event/type :tickets-reserved
   :data {:performance-id "oasis-dublin-2026"
          :customer-id "customer-1"
          :quantity 2}}]}
```

Nothing has been persisted and no effect has occurred merely because an accepted decision contains an event.

The event is still data.

---

# Part II — malformed input is not a business rejection

## 10. This distinction is fundamental

The repository deliberately separates:

```text
"I cannot understand this request"
```

from:

```text
"I understand this request, and the business says no"
```

Those are not the same thing.

### Invalid command

```clojure
{:command/type :reserve-tickets
 :data {:customer-id "customer-1"
        :quantity "three"}}
```

The command does not conform to its Malli schema.

The result is:

```clojure
{:result/type :invalid-command
 :spec/ref {...}
 :errors {...}}
```

This is **not** a rejected business decision.

No business rule has been evaluated.

### Valid command rejected by the business

```clojure
{:command/type :reserve-tickets
 :data {:customer-id "customer-1"
        :quantity 3}}
```

This is structurally valid.

If the customer already has two tickets and the limit is four, `BR-4` rejects it:

```clojure
{:result/type :decision
 :decision
 {:decision/type :rejected
  :rule/id :BR-4
  :reason :ticket-limit-exceeded
  :spec/ref {...}}}
```

The distinction is therefore:

```text
Malli asks:
"Is this shaped like a valid input to the domain decision?"

Business rules ask:
"Given a valid input, is the requested business transition allowed?"
```

Do not move ordinary business rules into Malli merely because Malli can express predicates.

For example:

```text
quantity is an integer
```

is structural validity.

But:

```text
the requested quantity must not make the customer exceed the ticket limit
```

is a business rule.

That belongs in `:rules`.

---

## 11. Invalid state is also separate

The public entry point validates state before command:

```clojure
(decider/decide prepared state command)
```

can return:

```clojure
{:result/type :invalid-state ...}
```

or:

```clojure
{:result/type :invalid-command ...}
```

or:

```clojure
{:result/type :decision
 :decision ...}
```

The current precedence is:

```text
1. semantic bundle must itself be valid;
2. state must be valid;
3. command must be valid;
4. only then are business rules evaluated.
```

All three results are described by `decider.schema/Result`, so a caller can
validate what it received rather than trusting the shape.

### The fourth outcome: `decide` can throw

There are three *results*. There are four *outcomes*, and the fourth is an
exception. Two things cause it, and they are the same kind of thing:

```text
the bundle is not valid          -> assert-valid-bundle! throws from prepare
the bundle cannot be evaluated   -> the interpreter throws from decide
```

The first is checked before anything runs. The second cannot be, because the
DSL is not statically typed (section 23): a bundle may be perfectly well formed
and still ask for `[:expr/+ nil 1]` at runtime, because a bundle's
`:state/schema` is an open Malli `:map` (section 17) and a path the rules read
is not necessarily a path that schema guarantees.

When that happens the interpreter throws `clojure.lang.ExceptionInfo` carrying
the specification reference and the rule, derivation or event template that
failed, with the original exception as its cause:

```clojure
{:spec/ref     {:id :amazon/add-item-to-basket :version 1 :hash "sha256:..."}
 :evaluating   "rule :BR-5"
 :rule/id      :BR-5
 :form         [:expr/<= ...]}
```

Both are defects in the specification rather than business outcomes, so neither
is turned into a decision — the same reasoning that governs an invalid bundle.

The usual cause of the second is a guard rule that has been moved. `:rule/after`
exists to catch that before it ships; see section 16.

---

# Part III — the semantic bundle

## 12. EDN is the authoritative business-rule representation

Each example domain command has a test-only semantic bundle under:

```text
test/resources/semantic-bundles/
```

There is deliberately **no parallel rules-as-code implementation** in the repository.

During exploration, rules-as-code and rules-as-data were useful to compare the two strategies. Keeping both permanently would create two authoritative representations that could drift apart.

For this repository the decision is:

> The EDN semantic bundle is authoritative. The Clojure code is generic interpretation machinery.

This is important for Codex: do not introduce a second Clojure implementation of each domain rule unless the explicit task is to create a temporary oracle for testing or comparison.

---

## 13. Semantic bundle shape

A bundle currently has this shape:

```clojure
{:spec/id ...
 :spec/version ...

 :rule-evaluation
 {:strategy :first-failure}

 :state/schema ...
 :command/schema ...

 :derive
 [[derived-name expression]
  ...]

 :rules
 [{:rule/id ...
   :rule/text ...
   :rule/after [rule-id ...]   ; optional
   :require expression
   :otherwise reason}
  ...]

 :events
 [event-template
  ...]}
```

Every part has a distinct role.

These are the only keys a bundle may have: `SemanticBundle` is `{:closed true}`,
so an unrecognised key is rejected rather than ignored (section 17). The one
addition is `:spec/hash`, which is never authored in the EDN — `bundle/load` and
`decider.core/prepare` attach it, and the schema permits it so that a bundle
already loaded still validates (section 30).

---

## 14. `:spec/id` — human semantic identity

Example:

```clojure
:spec/id :ticketmaster/reserve-tickets
```

This answers:

> What decision model is this?

It is a stable, qualified keyword intended to be understandable by humans and tools.

Do not replace it with a hash.

A content hash cannot communicate domain meaning and should not be the only identity of a specification.

---

## 15. `:spec/version` — governed human revision

Example:

```clojure
:spec/version 1
```

This answers:

> Which governed revision of this decision model is this?

The version is explicit and human-managed.

A hash and a human version solve different problems.

Recommended rule:

- bump `:spec/version` whenever the governed semantics of the bundle change;
- do not rely on the hash alone as a substitute for intentional version governance.

Semantically significant changes include, at minimum:

- state schema changes that alter accepted domain state;
- command schema changes that alter accepted commands;
- derivation changes;
- derivation order changes;
- business-rule expression changes;
- rule order changes under `:first-failure`;
- rejection reason changes if consumers rely on them;
- accepted event shape/content changes;
- rule-evaluation strategy changes.

The current hash also changes for documentary changes such as `:rule/text`, because it hashes the whole bundle other than `:spec/hash`. See the hashing section below.

---

## 16. `:rule-evaluation` — rule execution semantics

Every bundle currently declares:

```clojure
:rule-evaluation
{:strategy :first-failure}
```

This is deliberately explicit.

Previously, a loop could simply stop at the first failing rule and the behavior might look like an implementation detail.

It is not an implementation detail.

When several rules are false at the same time, returning the first one makes **rule order observable business behavior**.

Therefore the vector order of `:rules` is normative when the strategy is `:first-failure`.

Example:

```text
BR-1  auction must be open
BR-2  seller cannot bid
BR-3  bid must be high enough
```

If a closed auction receives a bid from its seller that is also too low, the result is `BR-1`, because `BR-1` is first.

Reordering these rules changes the externally visible rejection.

Consequences:

- do not sort rules automatically;
- do not convert `:rules` to a set or unordered map;
- do not parallelize rule evaluation without redefining the semantics;
- treat rule reordering as a semantic change;
- bump the bundle version when intentional reordering changes behavior.

### Guard rules, and `:rule/after`

Rule order does more than choose which rejection is returned. Some rules are
preconditions of later ones, and moving them is not merely a different answer —
it is a wrong one, or no answer at all.

Amazon's `BR-2` is "the product must exist". `BR-5` reads
`[:expr/get :derived [:product :max-per-order]]`. With `BR-2` first, a missing
product is rejected cleanly as `:product-not-found`. Move `BR-5` ahead of it and
the same request raises a `NullPointerException`, because `<=` is handed a nil.
Move `BR-2` to the end instead and it does something quieter and worse: it
answers `:product-not-purchasable` for a product that does not exist.

Nothing about `BR-5` says it depends on `BR-2`. So a rule may say so:

```clojure
{:rule/id :BR-5
 :rule/text "Adding the requested quantity must not exceed ..."
 :rule/after [:BR-2]
 :require [:expr/<= ...]
 :otherwise :maximum-order-quantity-exceeded}
```

`:rule/after` is a vector of rule ids that must appear **earlier** in `:rules`.
`decider.schema/problems` reports `:guard-rule-out-of-order` if one does not and
`:unknown-guard-rule` if it names a rule that is not there, so a reordering that
breaks a guard is refused at `prepare` rather than discovered in production.

It is optional and it is documentation as much as validation: writing it down is
what turns "everyone knows BR-2 has to come first" into something the machine
also knows. Add it wherever a rule reads a value that an earlier rule is what
makes safe.

Because a mechanism that can be switched off by a typo is not a mechanism, the
rule map is `{:closed true}`. `:rule/aftr` is rejected as an unknown key rather
than accepted as a guard that quietly does not exist, and the report names the
rule:

```clojure
{:problem :unknown-rule-key
 :rule/id :BR-3
 :keys    #{:rule/aftr}
 :known   #{:rule/id :rule/text :rule/after :require :otherwise}}
```

See section 17 for why these schemas are closed and the bundle's own
state and command schemas are not.

Only `:first-failure` exists today.

An `:all-failures` strategy might be useful in the future, but adding it would be a deliberate extension of the DSL semantics, not a refactoring.

---

## 17. `:state/schema` and `:command/schema`

These are Malli schemas stored directly in EDN.

Example:

```clojure
:command/schema
[:map
 [:command/type [:enum :reserve-tickets]]
 [:data
  [:map
   [:customer-id :string]
   [:quantity :int]]]]
```

Their job is structural validation.

They make it possible to:

- reject malformed state;
- reject malformed commands;
- explain validation failures;
- generate structurally valid states and commands using `malli.generator`;
- keep state and command shape close to the business semantics that depend on them.

They should not absorb every business invariant.

The schemas are part of the semantic bundle and therefore part of its content hash.

### Current Malli map behavior

The schemas currently use ordinary Malli `:map` forms without `{:closed true}`. Do not silently change this while refactoring. Closing maps would alter what inputs are accepted and is therefore a semantic decision.

### Open here, closed elsewhere

That rule is about **these** schemas, and only these. `:state/schema` and
`:command/schema` describe input arriving from a caller, and how tolerant a
domain is of unexpected keys is the bundle author's decision to make.

The schemas in `decider.schema` that describe structures *this project* writes —
`SemanticBundle`, `SpecificationRef`, `Decision`, `InvalidInput`, `Result` — are
all `{:closed true}`, for the opposite reason. Nobody extends those by adding a
key: an unrecognised key there is a typo, and an open map makes it a silent one.

The case that settled it was real. `:rule/aftr` validated perfectly while the
guard it was meant to declare simply did not exist, so the rule reordering that
`:rule/after` exists to catch went through and crashed at runtime — a safety
mechanism switched off by one character, with no warning. See section 16.

Two kinds of map, two opposite defaults, and the distinction is which side of
the boundary authored the shape.

---

# Part IV — the expression DSL

## 18. Why there is a DSL at all

EDN is data. It does not execute itself.

For example:

```clojure
[:expr/<=
 [:expr/get :derived [:resulting-quantity]]
 [:expr/get :state [:max-tickets-per-customer]]]
```

has no intrinsic computational meaning.

`decider.dsl/expression-value` defines what the form means.

This is deliberate:

```text
EDN contains business semantics as data.
Clojure contains the generic semantics of the small language.
```

The interpreter should know what `<=` means.

It should **not** know what a ticket limit, airline booking, registered title, basket, or Secret Santa assignment means.

---

## 19. Why the DSL does not use `eval`

Do not replace the interpreter with Clojure `eval`.

The explicit interpreter gives us:

- a bounded language;
- inspectable semantics;
- validation before execution;
- a controlled set of operations;
- safe EDN rather than arbitrary executable Clojure;
- a language that can be hashed and analyzed as data;
- the ability to reject unknown operators;
- a clear distinction between domain semantics and general-purpose computation.

Using `eval` would collapse those boundaries and effectively turn every semantic bundle into arbitrary program source.

---

## 20. Strong syntax boundary: `:expr/*`

Every executable expression is a vector whose first element is a keyword in the `expr` namespace.

Examples:

```clojure
[:expr/get :state [:status]]
[:expr/= a b]
[:expr/+ a b]
```

`expression?` is intentionally simple:

```clojure
(defn expression?
  [x]
  (and (vector? x)
       (keyword? (first x))
       (= "expr" (namespace (first x)))))
```

This gives the language a strong visual and structural boundary.

Ordinary data such as:

```clojure
[:economy :business]
```

is just a vector.

But:

```clojure
[:expr/unknown 1 2]
```

is clearly attempting to be executable DSL syntax.

The schema rejects an unknown `:expr/*` operator rather than silently treating it as literal data.

The runtime interpreter also throws for an unknown expression operator if invalid input somehow bypasses bundle validation.

This duplication is intentional defense in depth.

---

## 21. Current DSL operators

The DSL is intentionally small.

### `:expr/get`

```clojure
[:expr/get source path]
```

Sources are currently:

```clojure
:state
:command
:derived
```

Example:

```clojure
[:expr/get :command [:data :quantity]]
```

Dynamic path elements are expressions:

```clojure
[:expr/get
 :state
 [:sku->product
  [:expr/get :command [:data :sku]]]]
```

The path is evaluated before `get-in` is performed.

---

### `:expr/get-or`

```clojure
[:expr/get-or source path default]
```

Example:

```clojure
[:expr/get-or
 :state
 [:customer-id->tickets-reserved
  [:expr/get :command [:data :customer-id]]]
 0]
```

This makes domain defaults explicit rather than hiding them in interpreter code.

---

### `:expr/=`

```clojure
[:expr/= a b]
```

Uses Clojure equality semantics.

---

### `:expr/not=`

```clojure
[:expr/not= a b]
```

Uses Clojure `not=` semantics.

---

### `:expr/<=`

```clojure
[:expr/<= a b]
```

Used for numeric/business ordering such as:

```text
required bid <= offered bid
```

or:

```text
resulting quantity <= maximum allowed
```

---

### `:expr/+`

```clojure
[:expr/+ a b]
```

Adds two evaluated operands.

It is deliberately binary today. Do not broaden arity merely for convenience unless a real domain need justifies the language change.

---

### `:expr/nil?`

```clojure
[:expr/nil? x]
```

Used when absence itself is business-relevant, for example whether an auction has a highest bid or whether an airline booking lookup succeeded.

---

### `:expr/not`

```clojure
[:expr/not x]
```

Logical negation using Clojure truth semantics.

---

### `:expr/contains?`

```clojure
[:expr/contains? collection value]
```

This deliberately follows Clojure's `contains?` semantics.

That is important:

- for a set, it tests set membership;
- for a map, it tests whether a key is present;
- it should not be casually read as general sequential value membership.

For general collection-value membership the DSL has `:expr/member?`.

---

### `:expr/values`

```clojure
[:expr/values map]
```

Returns the map's values, as a **vector**.

`vals` alone yields a seq, and a seq is not a vector in the two places this
project treats the difference as real: it prints as `(a b)` and reads back as a
list, and `decider.hash/canonical` tags it `:list` rather than `:vector`. Every
other collection the interpreter produces is a vector, so this one is too. An
empty or absent map gives `[]`.

This was introduced for the Secret Santa domain so we could derive all already-assigned recipient IDs from the giver-to-recipient map.

The operation is generic; the interpreter contains no Secret Santa concept.

---

### `:expr/member?`

```clojure
[:expr/member? collection value]
```

Tests whether the evaluated value is equal to some element of the evaluated collection.

It exists separately from `:expr/contains?` because those concepts have different Clojure semantics.

---

### `:expr/if`

```clojure
[:expr/if condition then else]
```

Only the selected branch is evaluated, matching normal conditional semantics.

This is used for domain decisions such as computing a minimum bid differently depending on whether a previous bid exists.

---

## 22. Do not grow the DSL casually

The DSL is **not** intended to become a general-purpose programming language.

Before adding an operator, ask:

1. Is this really generic computation rather than domain vocabulary?
2. Is the need demonstrated by more than one domain, or is there a strong reason it belongs in the common language?
3. Can the requirement be expressed more clearly by changing the state shape or adding a named derivation?
4. Will the operator have simple, unsurprising semantics?
5. Can Malli validate its syntactic shape?
6. Can it remain pure?

Examples of bad interpreter additions:

```clojure
:expr/customer-ticket-limit
:expr/is-registered-owner?
:expr/airline-seat-available?
```

Those are domain concepts and belong in semantic bundles.

Examples of potentially reasonable generic additions, if justified by multiple domains:

```clojure
:expr/-
:expr/<
:expr/and
:expr/or
:expr/count
```

Even these should be added only when needed.

The restraint is part of the architecture.

---

## 23. Malli validates DSL shape, not full static types

This is an important current limitation and an intentional simplification.

`decider.schema` can verify things such as:

```text
:expr/+ has two operands
:expr/not has one operand
:expr/get has a valid source and path form
unknown :expr/* operators are invalid
```

It does **not** statically prove that both operands of every `:expr/+` will be numeric at runtime.

Likewise it does not prove every lookup path is valid for every possible state generated by the state schema.

Correct type relationships currently come from:

- the Malli state/command schemas;
- `:rule/after`, where one rule is what makes another safe (section 16);
- careful semantic-bundle design;
- example tests;
- generative tests;
- normal Clojure runtime semantics.

Do not add a large static type system to the DSL unless that is an explicit design goal. The current project values a small, understandable interpreter over trying to prove an entire embedded language statically.

The price of that choice is the fourth outcome in section 11: a bundle can be
valid and still fail at runtime. The interpreter does not pretend otherwise — it
catches the failure and rethrows it naming the rule, derivation or event
template responsible, so the cost is a diagnosable exception rather than a bare
`NullPointerException` from inside `clojure.core`.

---

# Part V — named derived values

## 24. Why `:derive` exists

Business rules often need intermediate concepts.

For Ticketmaster:

```text
already reserved tickets
+
requested tickets
=
resulting quantity
```

Repeating that expression inside several rules would obscure the business concept and create duplication.

So the semantic bundle can name derived values:

```clojure
:derive
[[:already-reserved
  [:expr/get-or
   :state
   [:customer-id->tickets-reserved
    [:expr/get :command [:data :customer-id]]]
   0]]

 [:resulting-quantity
  [:expr/+
   [:expr/get :derived [:already-reserved]]
   [:expr/get :command [:data :quantity]]]]]
```

Rules can then say:

```clojure
[:expr/<=
 [:expr/get :derived [:resulting-quantity]]
 [:expr/get :state [:max-tickets-per-customer]]]
```

This is much closer to the business language.

---

## 25. Derivation order is semantic

`:derive` is a vector of bindings:

```clojure
[[:a ...]
 [:b [:expr/get :derived [:a]]]]
```

They execute in vector order.

A later derivation may use an earlier derivation.

An earlier derivation may not refer to a later one.

This is deliberately similar to the semantic role of a Clojure `let`.

`decider.schema` checks this and reports undefined forward references.

Consequences:

- do not convert derivations to an unordered map;
- do not sort them;
- do not evaluate them in parallel without redefining semantics;
- derivation order changes can be semantic changes;
- duplicate derivation names are invalid.

---

## 26. What belongs in `:derive`

Good derivations usually have one or more of these properties:

- the expression is used by multiple rules;
- the concept has a useful business name;
- the expression is complex enough that naming improves comprehension;
- multiple lower-level facts combine into one decision-relevant fact.

Examples currently include:

```text
:minimum-bid
:booking
:seat
:already-reserved
:resulting-quantity
:product
:already-in-basket
:highest-bid-exists?
:required-bid
:assigned-recipient-ids
:excluded-recipient-ids
```

Do not create a derivation for every trivial lookup. Names should add meaning rather than merely add indirection.

---

# Part VI — business rules

## 27. Rule shape

Each business rule is explicit data:

```clojure
{:rule/id :BR-4
 :rule/text "The reservation must not cause the customer to exceed the per-customer ticket limit."
 :require
 [:expr/<=
  [:expr/get :derived [:resulting-quantity]]
  [:expr/get :state [:max-tickets-per-customer]]]
 :otherwise :ticket-limit-exceeded}
```

Each field has a different purpose.

### `:rule/id`

Stable rule identity within the semantic bundle.

Current bundles use identifiers such as:

```clojure
:BR-1
:BR-2
```

They need only be unique within the bundle because the enclosing `:spec/id` disambiguates them globally.

### `:rule/text`

Human-readable business meaning.

The interpreter does not execute this text, but it is part of the authoritative semantic bundle and part of its hash.

The rule text and executable `:require` expression must describe the same rule.

Codex must not casually update one without checking the other.

### `:require`

The executable condition that must be truthy for evaluation to continue.

Rules are written positively as requirements:

```text
The auction must be open.
The bidder must be eligible.
The transferor must be the registered owner.
```

This gives every rule a simple semantic shape:

```text
require condition
otherwise reject for reason
```

### `:rule/after`

Optional. The rule ids that must be evaluated, and pass, before this rule is
meaningful — see section 16. They must appear earlier in `:rules`, and the
validator enforces it.

Use it when this rule reads something an earlier rule is what guarantees:
a derived value that may be nil, a lookup that may miss. Do not use it as
general commentary on rule ordering; a rule that merely happens to follow
another does not depend on it.

A rule may carry these five keys and no others. The map is closed, so
`:rule/aftr` is a reported error rather than a guard that silently does not
exist.

### `:otherwise`

A machine-readable rejection reason.

Example:

```clojure
:ticket-limit-exceeded
```

This is distinct from `:rule/id`:

```text
:rule/id      -> identity of the business rule
:otherwise    -> business reason returned when it fails
```

---

# Part VII — accepted event templates

## 28. `:events` are output templates

Accepted decisions render one or more event maps from the current environment.

Example:

```clojure
:events
[{:event/type :tickets-reserved
  :data
  {:performance-id [:expr/get :state [:performance-id]]
   :customer-id    [:expr/get :command [:data :customer-id]]
   :quantity       [:expr/get :command [:data :quantity]]}}]
```

`template-value` recursively walks:

- maps;
- vectors;
- sets;
- seqs;
- embedded `:expr/*` forms.

It evaluates expressions and leaves ordinary values as data.

The output is still ordinary immutable data.

### Values are rendered. Keys are not.

`template-value` renders a map's **values** and copies its **keys** through
untouched. So this does not do what it looks like it does:

```clojure
{[:expr/get :state [:performance-id]] 1}
```

The key is not evaluated. It lands in the event as the literal vector
`[:expr/get :state [:performance-id]]`, which is almost certainly not what
anyone writing it meant, and nothing at runtime would say so.

Rather than leave a construct that is silently inert, the validator refuses it:

```clojure
{:problem :expression-in-template-key
 :key     [:expr/get :state [:performance-id]]
 :in      [:events 0]}
```

An expression nested anywhere inside a key is refused the same way, and the rule
applies to `:derive` values too, since they render through the same function.

Making keys renderable instead would be a deliberate extension of the language
rather than a fix — it introduces a new failure mode, since a rendered key can
evaluate to nil or to something that is not a keyword. That option stays open.
Refusing the form is what keeps it open: no bundle can come to depend on the
current do-nothing behaviour in the meantime.

### Current accepted-decision constraint

The `SemanticBundle` schema currently requires at least one event template:

```clojure
[:events
 [:vector {:min 1} EventTemplate]]
```

Each `EventTemplate` must be a map containing only valid template forms. This
keeps the bundle schema aligned with `Decision`, which promises that every
rendered event is a map. An accepted decision currently always emits at least
one event.

That is a current project constraint, not a universal law of `decide`. Changing it should be an explicit design decision.

---

# Part VIII — semantic identity: ID + version + hash

## 29. Why use all three

Every loaded semantic bundle has an identity of this form:

```clojure
{:id      :ticketmaster/reserve-tickets
 :version 1
 :hash    "sha256:..."}
```

Each field answers a different question.

```text
:id
What decision model is this?

:version
Which intentionally governed revision is this?

:hash
Exactly which content did we execute?
```

This is stronger than using any one of them alone.

---

## 30. Why the hash is not authored in the EDN file

The source bundle does not contain its own `:spec/hash`.

A document cannot straightforwardly contain a hash of itself without defining special exclusion/self-reference rules.

Instead:

1. the EDN resource is read;
2. it is validated;
3. a content hash is computed;
4. `:spec/hash` is associated with the in-memory specification.

`specification-hash` explicitly removes any existing `:spec/hash` before hashing, so recomputing a loaded bundle produces the same hash.

---

## 31. Why canonicalization exists

Clojure maps and sets should not acquire different semantic identities merely because their iteration order differs.

`decider.hash/canonical` converts data into a deterministic structural representation before hashing.

Conceptually:

```text
map    -> sorted canonical key/value pairs
set    -> sorted canonical members
vector -> ordered canonical members
list   -> ordered canonical members
scalar -> itself
```

The canonical representation is then printed and SHA-256 hashed.

The resulting string is prefixed:

```text
sha256:<64 hexadecimal characters>
```

---

## 32. What the current hash means

The current hash covers the complete parsed semantic bundle except for `:spec/hash` itself.

Therefore the hash changes if any hashed content changes, including:

- schemas;
- derivations;
- rule IDs;
- rule text;
- rule expressions;
- rule ordering;
- rejection reasons;
- event templates;
- version number;
- rule-evaluation strategy.

This is intentional content identity for the **whole bundle**, not only for executable expressions.

That means even a wording-only change to `:rule/text` changes the content hash.

Do not change this behavior accidentally.

If a future requirement distinguishes "semantic executable hash" from "whole artifact hash", introduce separate explicitly named hashes rather than silently changing the meaning of `:spec/hash`.

---

## 33. Hash caveat

The current canonicalization is a project-level deterministic representation implemented in Clojure.

It is not claimed to be an external, cross-language canonical EDN standard.

If semantic bundle identity must later be reproduced byte-for-byte by systems written in other languages, define and version a formal canonical serialization specification first.

Do not assume that today's `pr-str`-based canonical form is automatically an interoperability standard.

---

# Part IX — the seven example domains

## 34. Why several unrelated domains are included

The point of the examples is not to create realistic clones of eBay, Amazon, Ticketmaster, an airline, or a land registry.

The examples deliberately use small slices of each domain to test a more important hypothesis:

> Can the same generic decision machinery express materially different business decisions without absorbing domain-specific concepts?

So far the interpreter has remained generic while the bundles contain the domain meaning.

---

## 35. eBay clone — place a bid

Bundle:

```text
test/resources/semantic-bundles/ebay-place-bid.edn
```

Identity:

```clojure
:ebay/place-bid
```

Command:

```clojure
:place-bid
```

Derived value:

```clojure
:minimum-bid
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The auction must be open. | `:auction-not-open` |
| `BR-2` | The seller cannot bid on their own auction. | `:seller-cannot-bid` |
| `BR-3` | A bid must be at least the minimum acceptable bid. | `:bid-too-low` |

Accepted event:

```clojure
:bid-placed
```

This domain introduced the need for conditional derivation and arithmetic while keeping the interpreter completely unaware of auctions.

---

## 36. Airline — reserve a seat

Bundle:

```text
test/resources/semantic-bundles/airline-reserve-seat.edn
```

Identity:

```clojure
:airline/reserve-seat
```

Command:

```clojure
:reserve-seat
```

Derived values:

```clojure
:booking
:seat
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The flight must be open for seat reservations. | `:seat-reservations-closed` |
| `BR-2` | The passenger must have a booking on the flight. | `:booking-not-found` |
| `BR-3` | The passenger's booking must be confirmed. | `:booking-not-confirmed` |
| `BR-4` | The requested seat must exist. | `:seat-not-found` |
| `BR-5` | The requested seat must be available. | `:seat-not-available` |
| `BR-6` | The requested seat must be in the cabin booked by the passenger. | `:seat-not-in-booked-cabin` |

Accepted event:

```clojure
:seat-reserved
```

This domain demonstrates dynamic lookup paths based on command data.

---

## 37. Ticketmaster clone — reserve tickets

Bundle:

```text
test/resources/semantic-bundles/ticketmaster-reserve-tickets.edn
```

Identity:

```clojure
:ticketmaster/reserve-tickets
```

Command:

```clojure
:reserve-tickets
```

Derived values:

```clojure
:already-reserved
:resulting-quantity
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The ticket sale must be open. | `:ticket-sale-not-open` |
| `BR-2` | At least one ticket must be requested. | `:invalid-ticket-quantity` |
| `BR-3` | There must be enough tickets remaining to satisfy the request. | `:insufficient-tickets` |
| `BR-4` | The reservation must not cause the customer to exceed the per-customer ticket limit. | `:ticket-limit-exceeded` |

Accepted event:

```clojure
:tickets-reserved
```

This domain demonstrates a business default (`0` tickets previously reserved) and why repeated calculations deserve named derivations.

---

## 38. Amazon clone — add an item to a basket

Bundle:

```text
test/resources/semantic-bundles/amazon-add-item.edn
```

Identity:

```clojure
:amazon/add-item-to-basket
```

Command:

```clojure
:add-item
```

Derived values:

```clojure
:product
:already-in-basket
:resulting-quantity
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The basket must be open for modification. | `:basket-not-open` |
| `BR-2` | The product must exist. | `:product-not-found` |
| `BR-3` | The product must currently be purchasable. | `:product-not-purchasable` |
| `BR-4` | At least one unit must be requested. | `:invalid-quantity` |
| `BR-5` | Adding the requested quantity must not exceed the maximum quantity permitted per customer order. | `:maximum-order-quantity-exceeded` |
| `BR-6` | There must be sufficient stock for the resulting quantity in the basket. | `:insufficient-stock` |

Accepted event:

```clojure
:item-added-to-basket
```

This domain reused the existing language without adding any Amazon-specific interpreter behavior.

---

## 39. Land registry — register a transfer

Bundle:

```text
test/resources/semantic-bundles/land-registry-register-transfer.edn
```

Identity:

```clojure
:land-registry/register-transfer
```

Command:

```clojure
:register-transfer
```

Derived values:

```clojure
[]
```

There are deliberately no derivations here because the rules are already simple enough to read directly.

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The title must be registered and capable of being dealt with. | `:title-not-registrable` |
| `BR-2` | The transferor must be the currently registered owner. | `:transferor-not-registered-owner` |
| `BR-3` | The transferee must be different from the transferor. | `:transferor-and-transferee-identical` |
| `BR-4` | The title must not contain a restriction prohibiting transfer. | `:transfer-prohibited` |
| `BR-5` | The transfer instrument must have been validly executed. | `:transfer-instrument-not-executed` |
| `BR-6` | The required registration fee must have been paid. | `:registration-fee-not-paid` |

Accepted event:

```clojure
:title-transferred
```

This is a deliberately simplified land-registry example, not a claim to model the law of a particular jurisdiction.

It demonstrates set/key membership while preserving the same generic decision machinery.

---

## 40. Property bidding — place a bid

Bundle:

```text
test/resources/semantic-bundles/property-bidding-place-bid.edn
```

Identity:

```clojure
:property-bidding/place-bid
```

Command:

```clojure
:place-bid
```

Derived values:

```clojure
:highest-bid-exists?
:required-bid
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The property must be open for bidding. | `:bidding-not-open` |
| `BR-2` | The seller cannot bid on their own property. | `:seller-cannot-bid` |
| `BR-3` | The bidder must be eligible to bid. | `:bidder-not-eligible` |
| `BR-4` | If there is no existing highest bid, the bid must meet the property's minimum bid. | `:minimum-bid-not-met` |
| `BR-5` | If a highest bid exists, the new bid must exceed it by at least the minimum increment. | `:minimum-increment-not-met` |

Accepted event:

```clojure
:property-bid-placed
```

This domain demonstrates conditional rules and explicit eligibility while reusing the generic collection and conditional operations already present.

---

## 41. Secret Santa — assign a recipient

Bundle:

```text
test/resources/semantic-bundles/secret-santa-assign-recipient.edn
```

Identity:

```clojure
:secret-santa/assign-recipient
```

Command:

```clojure
:assign-recipient
```

Derived values:

```clojure
:assigned-recipient-ids
:excluded-recipient-ids
```

Business rules:

| ID | Rule | Rejection reason |
|---|---|---|
| `BR-1` | The exchange must be open for assignments. | `:assignments-not-open` |
| `BR-2` | The giver must be a participant. | `:giver-not-participant` |
| `BR-3` | The recipient must be a participant. | `:recipient-not-participant` |
| `BR-4` | A participant cannot be assigned to themselves. | `:cannot-assign-self` |
| `BR-5` | The giver must not already have a recipient. | `:giver-already-assigned` |
| `BR-6` | The recipient must not already be assigned to another giver. | `:recipient-already-assigned` |
| `BR-7` | The recipient must not be excluded for that giver. | `:recipient-excluded` |

Accepted event:

```clojure
:recipient-assigned
```

This domain motivated the distinction between Clojure `contains?` semantics and general value membership, hence the generic `:expr/values` and `:expr/member?` operations.

---

# Part X — namespace responsibilities

## `decider.identity`

Purpose:

```text
the identity triple every result carries
```

Responsibility:

- project `{:id :version :hash}` from a specification.

That is the whole namespace. It is unnumbered because it was extracted after
the sections below were written, and renumbering forty sections to insert it
would have been the more disruptive change.

It exists because three different results — a decision, an invalid command, an
invalid state — all carry `:spec/ref`, and the projection was previously
written out three times: once in `decider.bundle`, once privately in
`decider.dsl`, and once inline in `decider.core`. Only the first was public and
only the copy inside decisions was ever checked against
`decider.schema/SpecificationRef`, so a fourth identity field, or a change to
one copy, would have produced results whose `:spec/ref` quietly meant different
things depending on which branch produced them.

It has no dependencies. That is the point: `decider.dsl` and `decider.core` both
need it and they sit on opposite sides of `decider.schema`, so neither can own
it without something reaching across the layering.

`decider.identity-test` checks that the constructed shape and
`decider.schema/SpecificationRef` still agree, and that all three result types
carry the same reference.

---

## 42. `decider.bundle`

Purpose:

```text
resource loading and semantic-bundle construction
```

Responsibilities:

- locate EDN resources;
- read resource text;
- parse EDN, insisting on exactly one form;
- validate the semantic bundle;
- compute and associate `:spec/hash`;
- provide `load-prepared` for the common case.

### One form, not the first form

`edn/read-string` was the obvious way to parse a bundle and the wrong one: it
returns the **first** form in the text and discards the rest without complaint.
A bundle file that ended up holding two maps — a bad merge, a stray paste —
would have loaded as whichever came first, and nothing anywhere would have said
so. The file is now read form by form and refused unless it holds exactly one.
An empty file, or one containing only comments, is refused too rather than
becoming `nil`.

### `load-prepared`, and validating once

`load` validates what it read. `decider.core/prepare` validates what it was
given, because it also accepts hand-built specifications. Both are right on
their own, and together on the `load` then `prepare` path they check the same
bundle twice.

`load-prepared` removes the duplication by skipping the intermediate rather than
by weakening either: it reads the resource and prepares it, validating once.
A test asserts it produces exactly what `load` then `prepare` does, so it cannot
become a second way of building a prepared specification.

The runtime namespace deliberately has no example catalog. The hand-maintained
list of example paths and its `load-all` helper live in `decider.fixtures` on
the test classpath. `decider.bundle-test` asserts that list matches
`test/resources/semantic-bundles` exactly in both directions, so an untested
example is a build failure without adding demo assets to consumer applications.

`specification-ref` used to live here and now lives in `decider.identity`. This
namespace is the project's I/O edge, and a pure projection over a map already
in memory was never I/O; keeping a copy here as well as in the interpreter is
what allowed the three definitions to drift apart.

This is the primary I/O edge in the project.

The name `load` is appropriate because the function crosses a data-scope boundary and actually loads a resource.

Do not move domain rules into this namespace.

---

## 43. `decider.core`

Purpose:

```text
public validated decision entry point
```

Two functions, split along the line between what depends on the specification
and what depends on the request.

`prepare` does everything that depends only on the bundle, once:

1. assert that the semantic bundle is valid;
2. compute semantic identity;
3. compile the Malli state and command schemas into validators and explainers.

`decide` does the rest, per request:

1. refuse anything that is not a prepared specification;
2. validate state with the compiled state validator;
3. validate command with the compiled command validator;
4. return an invalid-input result where appropriate, stamped with
   `decider.identity/specification-ref`;
5. otherwise delegate the pure business decision to `decider.dsl/decision`.

`prepare-and-decide` is the two composed, for a single decision.

### Why they are separate

A bundle is immutable. Hashing it and re-validating it on every request buys
nothing, and it is not cheap: it was about 79% of the cost of a decision, most
of that the SHA-256 over the whole bundle. Malli also compiles a schema every
time it is handed one as raw data.

Measured on the Ticketmaster bundle:

```text
raw specification per call    0.1404 ms     7,120 decisions/sec
prepared per call             0.0039 ms   253,539 decisions/sec
prepare, once                 0.1246 ms
```

The gap widened as validation got stricter, which is the point: checks worth
running once per bundle are not worth running once per request.

### `decide` refuses a plain bundle

It used to accept either, preparing a raw bundle on the spot. That was the
wrong default: it made the expensive call the one that looks normal, and the
cheap one an optimisation you had to know about.

Now the two paths are named for what they cost:

```clojure
(decider/decide prepared state command)              ; 0.0039 ms
(decider/prepare-and-decide bundle state command)    ; 0.1404 ms
```

`decide` given a plain bundle throws, and says what to call instead. Without
that check it reached `(nil state)` and failed as an unexplained
`NullPointerException`, since a raw bundle has no `:prepared/state-valid?`. Two
map lookups on a path that costs four microseconds is a fair price.

`prepare-and-decide` is exactly `prepare` then `decide` — a test asserts it,
so the convenience path cannot become a second implementation that drifts.

The two are deliberately asymmetric about tolerance. `decide` refuses anything
unprepared, because there a mistake costs performance on every request.
`prepare` is idempotent and returns an already-prepared specification unchanged,
because there calling twice costs nothing and a boundary should be able to
prepare defensively without knowing what it was handed.

### Validation happens before hashing

The order matters, and it is not the order the responsibilities were originally
listed in. `decider.hash/canonical` walks the bundle recursively, so a bundle
nested too deeply to walk has to be *refused* before anything tries to walk it —
otherwise the depth limit in `decider.schema` is enforced by a stack overflow.

The prepared value is an opaque, lookup-only value holding compiled functions
under `:prepared/*` keys. It deliberately does not support `assoc`: changing
the embedded specification independently of its cached validators and hash
would let a decision claim an identity for rules it did not execute. It is
machinery, not semantic data: do not print it, serialize it, or hash it.
`decider.core/specification` gets the data back out.

It deliberately distinguishes input validation results from business decisions.

Do not put domain-specific rules here.

---

## 44. `decider.dsl`

Purpose:

```text
pure computational semantics of the EDN decision language
```

Responsibilities:

- identify DSL expression forms;
- evaluate generic expressions;
- evaluate dynamic paths;
- recursively render templates;
- compute ordered named derivations;
- find the first failed rule;
- construct accepted/rejected decision data;
- attach the semantic specification reference to decisions, using
  `decider.identity/specification-ref`.

This is the heart of the generic interpreter.

It must remain domain-blind and effect-free.

---

## 45. `decider.schema`

Purpose:

```text
validate the language and bundle structure
```

Responsibilities:

- define legal expression sources;
- validate operator arity and expression structure;
- validate nested template forms;
- verify embedded Malli schemas are themselves valid Malli schemas;
- define Malli schemas for `SpecificationRef`, `Decision`, `InvalidInput`,
  `Result`, and `SemanticBundle` (`SpecificationRef` describes the shape
  `decider.identity` constructs, so the two must change together);
- reject duplicate rule IDs;
- reject duplicate derivation names;
- detect derivations that refer to future/undefined derivations;
- detect rules/events that reference undefined derived values;
- detect guard rules declared by `:rule/after` that are missing or out of order;
- reject unknown keys on a rule, naming the rule (section 16);
- reject `:expr/*` forms in template keys, which are not rendered (section 28);
- reject a bundle nested deeper than `max-depth` or holding more values than
  `max-nodes`, before anything walks it;
- throw a useful exception for an invalid semantic bundle.

### Depth and size, both

Depth alone does not bound a bundle. An event template with two hundred thousand
keys nests three levels and passes a depth check, then gets walked in full by
canonicalization, hashing, template validation and the expression scan. So there
are two limits, and both are checked first, before anything else looks at the
bundle:

```text
max-depth   100        real bundles nest 8 to 9
max-nodes   100,000    real bundles hold 192 to 278 values
```

Both checks are iterative rather than recursive, because a recursive check would
overflow on exactly the input it exists to reject.

### Saying what is wrong, not only that something is

Malli's `[:fn ...]` predicates can only answer yes or no, so every check worth
explaining is also written out longhand. `problems` returns plain data — no
schema objects, no exceptions — that can be logged and read back:

```clojure
{:problem :wrong-operand-count :operator :expr/+ :expected 2 :actual 1
 :in [:rules :BR-4]}

{:problem :unknown-source :operator :expr/get :source :database
 :known #{:state :command :derived} :in [:derive :total]}

{:problem :invalid-malli-schema :schema/key :state/schema
 :reason :malli.core/invalid-schema :offending-form ":not-a-real-schema"}
```

`:in` says where in the bundle it is. The audience for these is whoever wrote
the bundle, and "must be a valid `:expr/*` expression" told them nothing.

`operand-counts` is the single list of the operators the language has, and
`decider.validation-test` checks it against the interpreter — the invariant
below is tested rather than merely asserted.

### A small public surface

Everything public is something not to break, so most of this namespace is not.
What is:

```text
problems, assert-valid-bundle!   ask whether a bundle is executable
SemanticBundle                   the shape of a bundle
Result, Decision,                the shapes decider.core/decide returns
  InvalidInput, SpecificationRef
sources, operand-counts,         what the language and its structures admit
  rule-keys, max-depth
```

The `[:fn ...]` predicates and the per-check problem functions are machinery and
are private. Use `problems`: it runs all of them and says more than any of them
does alone. `decider.dsl` is narrowed the same way — `decision` is the entry
point, and the phases it runs are its own business.

A crucial invariant is:

> The language accepted by `decider.schema` and the language executed by `decider.dsl` must stay synchronized.

When adding a new operator, update both namespaces and tests in the same change.

---

## 46. `decider.hash`

Purpose:

```text
deterministic content identity for semantic bundles
```

Responsibilities:

- canonicalize Clojure data;
- SHA-256 hash that canonical representation;
- exclude `:spec/hash` itself from specification hashing.

It has no domain knowledge.

`canonical` is recursive and has no depth guard of its own. It does not need
one *here*, because `decider.schema/problems` refuses a bundle nested past
`max-depth` and `decider.core/prepare` validates before it hashes. That is a
precondition rather than a property: called directly on arbitrarily nested data,
`canonical` will overflow the stack.

---

# Part XI — REPL-driven development

## 47. Start the development REPL

From the project root:

```bash
bb dev          # or: clojure -M:dev
```

Open:

```text
dev/decider/playground.clj
```

The file contains one rich `(comment ...)` form intended to be evaluated expression by expression.

This is the preferred learning and exploration workflow.

---

## 48. Typical REPL workflow

Load a bundle:

```clojure
(def ticketmaster
  (bundle/load
   "semantic-bundles/ticketmaster-reserve-tickets.edn"))
```

`prepare-and-decide` takes it as it is, preparing it on every call — the right
trade at a REPL, where one call costs a tenth of a millisecond and clarity is
worth more than speed.

For the fast path of section 43, keep the prepared value under its own name.
It is machinery rather than data, so the two are worth keeping apart:

```clojure
(def prepared-ticketmaster
  (bundle/load-prepared "semantic-bundles/ticketmaster-reserve-tickets.edn"))

;; and to get the specification back out
(decider/specification prepared-ticketmaster)
```

`load-prepared` reads and prepares in one step, validating once rather than
twice — `(decider/prepare ticketmaster)` on the bundle above gives the same
result and re-checks what `load` already checked.

`decide` takes the prepared value and refuses the plain bundle. Both appear
below.

Inspect its identity:

```clojure
(identity/specification-ref ticketmaster)
```

Define state:

```clojure
(def ticketmaster-state
  {:performance-id "oasis-dublin-2026"
   :sale-status :open
   :tickets-remaining 100
   :max-tickets-per-customer 4
   :customer-id->tickets-reserved
   {"customer-1" 2}})
```

Define a command:

```clojure
(def ticketmaster-command
  {:command/type :reserve-tickets
   :data {:customer-id "customer-1"
          :quantity 2}})
```

Ask the domain to decide:

```clojure
(decider/prepare-and-decide
 ticketmaster
 ticketmaster-state
 ticketmaster-command)
```

or, having kept `prepared-ticketmaster` from above:

```clojure
(decider/decide
 prepared-ticketmaster
 ticketmaster-state
 ticketmaster-command)
```

Then alter one fact at a time and evaluate again.

For example, use `:quantity 3` to observe a business rejection, then use `"three"` to observe malformed-input handling.

That comparison is more important than running the entire project at once.

---

# Part XII — testing

## 49. Run the tests

```bash
bb test         # or: clojure -M:test
```

The tests are ordinary `clojure.test`. Kaocha runs them, and it finds every
`*-test` namespace under `test/` by itself, so adding a test is adding a file
and nothing else — there is no runner namespace to remember to update.

To run one namespace, or one test:

```bash
clojure -M:test --focus decider.interpreter-test
clojure -M:test --focus decider.interpreter-test/first-failure-semantics-are-explicit
```

The isolated consumer check starts from its own `deps.edn`, depends on this
project through `:local/root`, loads a consumer-owned resource, exercises all
three result classes, and proves that test examples are not runtime assets:

```bash
bb consumer:smoke
```

`bb verify` runs format, lint, the full unit/property suite, and that consumer
check. The repository-root GitHub Actions workflow runs it on Java 21 and 25,
then performs the dependency checks described in the production support
contract.

---

## 50. Example tests

`interpreter_test.clj` currently verifies important architectural contracts.

### Every bundle validates

For every loaded bundle:

- `schema/problems` is empty;
- a `:spec/hash` exists;
- the hash starts with `sha256:`.

### Malformed input is not business rejection

A Ticketmaster command with:

```clojure
:quantity "three"
```

must produce:

```clojure
:result/type :invalid-command
```

and no business `:decision`.

### Valid requests can be rejected

A structurally valid command that violates the ticket limit must produce:

```clojure
:decision/type :rejected
:rule/id :BR-4
:reason :ticket-limit-exceeded
```

### First-failure behavior is explicit

The eBay test deliberately constructs an input that violates several business rules and verifies that `BR-1` wins because the bundle explicitly declares `:first-failure`.

### Semantic identity is one thing, not three

`identity_test.clj` covers Part VIII. It checks that the reference built by
`decider.identity` still matches `decider.schema/SpecificationRef`, that it
carries exactly the three documented keys, and — the reason the namespace
exists — that a decision, an `:invalid-command` result and an `:invalid-state`
result all carry the *same* `:spec/ref`.

It also pins the hashing claims: the prefix, idempotence under reload
(section 30), independence from map iteration order (section 31), and the fact
that a wording-only change to `:rule/text` still changes the hash (section 32).

### The validator is tested in the negative

`validation_test.clj` covers what `schema/problems` *refuses*, which nothing did
before. The validator is the largest and subtlest part of the project, and a
validator nobody has watched fail is a validator nobody knows works.

It checks that each structural problem is detected, that expression problems
name the operator, the arity and the place in the bundle, that a broken embedded
schema reports the sub-form that is not a schema, that problems survive a round
trip through EDN, that a bundle too deep to walk is refused rather than walked,
and that `:rule/after` catches the two reorderings of Amazon's rules that used
to produce a crash and a wrong answer respectively.

It also checks the section 45 invariant directly: every operator
`schema/operand-counts` admits is one `decider.dsl` implements.

Two of its tests exist because the thing they check was once silently wrong:
a `:rule/aftr` typo that disabled a guard while validating cleanly, and an
`:expr/*` form in a template key that was copied through unevaluated. Both are
now reported, and both are pinned here.

### The bundle list matches the bundle directory

`bundle_test.clj` compares `decider.fixtures/resource-paths` against the contents
of `test/resources/semantic-bundles`, in both directions. It guards the one place
where a mistake produces no symptom at all: a bundle file that exists, loads,
and is tested by nothing.

It also covers the reading itself — a file holding two forms, and a file holding
none — using fixtures in `test/resources/bundle-fixtures/` rather than files written into
the resource directory at test time, which would have raced with the listing
test above.

---

## 51. Generative testing

`generative_test.clj` uses:

```text
Malli generators
+
clojure.test.check generators
```

to generate valid states and commands from every bundle's Malli schemas.

For each generated pair it asserts:

```text
valid state + valid command
    -> :result/type :decision
```

and verifies that the returned decision conforms to `schema/Decision` and the
whole result to `schema/Result`. A second property asserts the interpreter does
not throw on anything the schemas admit — the shipped bundles are total even
though the language does not guarantee it (section 23).

This is useful because it exercises a broad range of input shapes without hand-writing every example.

### It is a `defspec`, and that matters

It is written with `defspec` and `prop/for-all` rather than a loop over
`gen/generate`. The difference only shows up when it fails, which is when it
counts: `defspec` prints the seed and shrinks the input to a minimal failing
case, so a red build is a reproduction. A loop over `gen/generate` prints a
forty-key generated map and no way to run it again.

The bundle is carried through the property as its `:spec/id`, not as the
prepared specification, so a failing case prints as something readable:

```clojure
[:amazon/add-item-to-basket {:basket-id "" ...} {:command/type :add-item ...}]
```

Bundles are prepared and generators built once, outside the property. Doing
either inside it would make the run dominated by compilation rather than by
decisions.

### What the current generative test does NOT prove

Be precise about this.

The current generative test is primarily a **structural/interpreter robustness property**.

It does not prove that every business rule is legally or commercially correct.

It does not prove the natural-language `:rule/text` is equivalent to the executable expression.

It does not prove every accepted event is sufficient for some future aggregate or event store.

It does not currently use a parallel rules-as-code implementation as a differential oracle, because EDN is the single authoritative representation in this repository.

Future domain-specific properties could add stronger semantic checks, for example:

```text
An accepted Ticketmaster decision never exceeds the ticket limit.
An accepted airline seat reservation never selects a seat from another cabin.
An accepted Secret Santa assignment never assigns giver to self.
An accepted land transfer always names the current registered owner as transferor.
```

Those would complement, not replace, the executable business rules.

---

# Part XIII — how to change the system safely

## 52. Adding a new semantic bundle

When adding a new domain decision:

1. Identify **one state-changing business request**.
2. Give it a qualified `:spec/id`.
3. Start at `:spec/version 1`.
4. Define the minimum state shape needed to decide it.
5. Define the command shape.
6. Write the business rules first in plain language.
7. Give each rule a stable `:rule/id`.
8. Order rules deliberately according to `:first-failure` semantics.
9. Identify useful named derived values.
10. Express the derivations and rule requirements using the existing DSL if possible.
11. Declare `:rule/after` wherever a rule reads something an earlier rule is what makes safe.
12. Define accepted event templates.
13. Load the bundle at the REPL.
14. Exercise accepted, rejected, invalid-command, and invalid-state examples.
15. Add focused tests.
16. For a repository example, add the resource path to `decider.fixtures/resource-paths` so it participates in the generic tests.
17. Add a rich-comment example to the playground if the domain is part of the teaching/demo set.

Step 16 used to be easy to forget and silent when forgotten: the fixture
`load-all` is what the generative test iterates, so a bundle missing from
`resource-paths` is a bundle nothing tests. `decider.bundle-test` compares the
test-only list against the test resource directory in both directions, so
forgetting it fails the build instead.

Do **not** begin by adding DSL operators. Try to model the domain with the existing language first.

---

## 53. Changing an existing bundle

Before editing an existing bundle, classify the change.

### Documentary-only intent

Examples:

- spelling correction in `:rule/text`;
- clearer wording that does not intend to change executable behavior.

Note that the current whole-bundle hash will still change.

Decide explicitly whether the governed `:spec/version` should also change according to project governance. Do not let Codex silently make that policy decision.

### Declaratory intent

A third case, between the two. Adding `:rule/after` to an existing rule declares
a dependency the bundle already had: no decision changes for any input, and the
constraint being written down was true before it was written down. The hash
changes, as it does for any edit.

The bundles here carry `:rule/after` at `:spec/version 1` on that reading — the
declaration did not change what they decide. That is a governance call and not
an obvious one, so it is recorded here rather than assumed. If your governance
treats any hash change as a revision, bump the version instead.

### Semantic change

Examples:

- change rule condition;
- add/remove rule;
- reorder rules;
- change derivation;
- change state or command schema;
- change event template;
- change rejection reason;
- change evaluation strategy.

For a semantic change:

1. update the business rule text where relevant;
2. update the executable expression;
3. bump `:spec/version`;
4. update examples/tests;
5. verify the new hash differs;
6. verify old behavior changes only where intended.

---

## 54. Adding a DSL operator

Treat this as a language change.

A complete operator change normally requires:

1. define the operator's exact semantics;
2. add execution to `decider.dsl/expression-value`;
3. add syntax validation to `decider.schema/valid-expression?`;
4. decide operand arity;
5. decide evaluation order/laziness if relevant;
6. add tests for valid use;
7. add tests for malformed arity/form;
8. use it in a semantic bundle only after the generic semantics are clear;
9. document it in this README.

Never add execution support without validation support, or validation support without execution support.

---

# Part XIV — design decisions and their rationale

## 55. Decision: rules are data in this project

**Choice**

Domain business rules are represented in EDN.

**Why**

- rules become inspectable data;
- they can be versioned and hashed;
- they can carry IDs and human text next to executable semantics;
- the same generic interpreter can execute unrelated domains;
- the authoritative business semantics are not spread through framework code.

**Trade-off**

A DSL and interpreter must now be designed and maintained.

This is why the DSL must remain small.

---

## 56. Decision: no duplicate rules-as-code implementation

**Choice**

EDN is the single authoritative representation.

**Why**

Maintaining both rules-as-code and rules-as-data indefinitely creates two sources of truth and eventual drift.

**Trade-off**

We lose an always-present differential testing oracle.

Temporary code implementations can still be useful during investigation, but they should not quietly become production authority alongside the EDN.

---

## 57. Decision: Malli handles shape, business rules handle acceptability

**Choice**

Malli validates state, command, DSL, and bundle structure; business rules determine whether a valid request is allowed.

**Why**

It preserves the difference between malformed data and a meaningful business refusal.

**Trade-off**

There is judgment involved in deciding whether a constraint is structural or business-semantic.

When in doubt, ask:

> Is this saying the input cannot be understood as this kind of command/state, or is it saying the business understands it and refuses it?

---

## 58. Decision: rule order is explicit

**Choice**

`:first-failure` is declared in the bundle.

**Why**

The first reported business failure is observable behavior when several rules fail simultaneously.

**Trade-off**

Rule ordering becomes part of the governed semantics and must be maintained intentionally.

---

## 59. Decision: named ordered derivations

**Choice**

Intermediate domain concepts live in ordered `:derive` bindings.

**Why**

- avoids repeated expressions;
- gives calculations meaningful business names;
- keeps rules readable;
- resembles the semantic usefulness of Clojure `let` bindings;
- exposes dependency order explicitly.

**Trade-off**

Derivation names and order become additional semantic surface area.

---

## 60. Decision: qualified `:expr/*` syntax

**Choice**

All executable DSL forms begin with a namespaced `:expr/*` keyword.

**Why**

- executable syntax is visibly distinct from ordinary EDN;
- unknown expressions can be rejected;
- ordinary vectors remain ordinary data;
- accidental interpretation is reduced.

**Trade-off**

The EDN is slightly more verbose, deliberately so.

---

## 61. Decision: explicit interpreter, no macros, no `eval`

**Choice**

A small Clojure function interprets a bounded expression language.

**Why**

- semantics remain explicit;
- syntax can be validated;
- data stays inspectable;
- arbitrary code execution is excluded;
- the language remains domain-independent.

**Trade-off**

Every language feature must be implemented explicitly.

That cost is useful pressure against accidental language growth.

---

## 62. Decision: ID + version + SHA-256 hash

**Choice**

Semantic identity has three parts.

**Why**

Human meaning, governance, and exact content identity are different concerns.

```text
ID      -> semantic name
version -> governed revision
hash    -> exact content identity
```

**Trade-off**

There is more metadata and governance to maintain, but the meaning is substantially clearer than overloading one identifier.

---

## 63. Decision: the hash covers the whole bundle

**Choice**

Everything except `:spec/hash` participates in the hash.

**Why**

The hash identifies the complete semantic artifact actually loaded, including its human-readable rule text and schemas.

**Trade-off**

Non-executable wording changes alter the hash.

If that becomes undesirable, do not silently narrow the existing hash. Introduce a separately named hash with separately documented semantics.

---

## 64. Decision: events remain data and event sourcing remains optional

**Choice**

Accepted decisions return event-shaped facts, but this project does not persist or replay them.

**Why**

The `decide` pattern is useful independently of event sourcing.

**Trade-off**

This repository cannot demonstrate state evolution, event replay, concurrency, or persistence behavior—and deliberately does not try to.

---

# Part XV — alignment with Elements of Clojure

## 65. Keep data shapes visible

The project uses ordinary:

- maps;
- vectors;
- sets;
- keywords;
- strings;
- numbers;
- booleans.

Do not hide them behind unnecessary records, wrappers, object hierarchies, protocols, or builder APIs.

The shape of the semantic data should remain visible to a reader.

---

## 66. Prefer narrow, meaningful names

Names such as:

```clojure
:resulting-quantity
:minimum-bid
:registered-owner-id
:assigned-recipient-ids
```

communicate business sense.

Avoid names that expose incidental machinery when a domain name is clearer.

Conversely, generic interpreter concepts should remain generic and should not borrow terminology from one example domain.

---

## 67. Keep transform separate from effects

The interpreter should remain a pure transformation over immutable data.

The only obvious operational concern in this project is loading resources.

Do not let database access, logging decisions, clocks, random values, HTTP calls, or message publication leak into `decider.dsl`.

---

## 68. Do not introduce indirection without meaning

Abstraction is valuable when it lets a reader ignore implementation details while preserving meaning.

It is not valuable merely because more layers can be created.

Examples:

- `:derive` is useful because it gives repeated/complex domain expressions names;
- a protocol for every DSL operator would probably add machinery without clarifying the current problem;
- a macro that makes EDN look shorter could make the language harder to inspect and reason about.

Keep the system small enough that the important semantic path can be understood directly.

---

# Part XVI — known limitations and deliberate simplifications

## 69. The examples are intentionally small

The seven domains are not full business systems.

They exist to exercise the decision abstraction.

Do not infer that a real airline, land registry, e-commerce site, or ticketing platform could be modeled by only these rules.

---

## 70. The DSL is dynamically typed

As described earlier, syntax is validated but full expression typing is not statically proven.

Badly designed bundles can still cause runtime type errors even if the AST shape is legal.

That is a known limitation, and it is why `decide` has a fourth outcome
(section 11). Two things narrow it without pretending to close it:

- `:rule/after` makes the common cause — a guard rule moved after the rule it
  guards — a validation error rather than a runtime one (section 16);
- the interpreter catches runtime failures and rethrows them naming the rule,
  derivation or event template responsible, so the failure is diagnosable.

What remains uncovered is a bundle that reads a path its own state schema does
not guarantee, with no guard rule involved. A bundle's `:state/schema` is an
open Malli `:map` and stays that way for the reasons in section 17, so
validation cannot catch it, and only a real type system could.
The generative test asserts the shipped bundles do not do this; nothing prevents
a new one from doing it.

---

## 71. No concurrency semantics exist here

Purity does not solve concurrent updates.

A real application must ensure that the state supplied to `decide` is coherent and that an accepted change is committed against the state/version that was actually decided upon.

Optimistic concurrency is one possible solution, but it belongs outside this repository.

Do not add locks, transactions, or database version checks to the DSL interpreter.

---

## 72. No temporal source exists inside `decide`

There is no `now()` operator.

If a business rule depends on time, the relevant time fact should be supplied as data.

For example:

```clojure
{:command/type :place-bid
 :data {...
        :decision-time #inst "..."}}
```

or as another explicitly modeled input fact, depending on domain design.

The important rule is that the pure decision must not secretly acquire time from the environment.

---

## 73. No random/identity generation exists inside `decide`

If a future event needs an externally generated identifier, supply it as a fact/input or return a descriptor to be interpreted outside the pure decision boundary.

Do not call random UUID generation from the interpreter.

---

## 74. Rule text is not mechanically proved equivalent to rule expression

The project places human-readable and executable semantics next to each other:

```clojure
:rule/text
:require
```

That proximity is valuable but not a formal proof of equivalence.

Reviews and tests must ensure they stay aligned.

A future traceability system could link `:rule/id` to externally governed requirements, legislation, acceptance criteria, or evidence without changing the basic decision model.

---

## 75. The current hash is not a legal-signature scheme

SHA-256 content identity can show that two bundle contents are identical under the project's canonicalization.

It does not by itself prove:

- who approved the bundle;
- who authored it;
- whether it was legally authorized;
- when it became effective;
- whether it was deployed;
- whether a cryptographic signature is authentic.

Those are governance/security concerns that could later be layered around the bundle.

Do not misrepresent `:spec/hash` as a digital signature.

---

# Part XVII — Codex working agreement

## 76. What Codex should preserve by default

Unless the task explicitly changes one of these decisions, preserve all of the following:

1. **Clojure only.**
2. **EDN is the authoritative domain rule representation.**
3. **The interpreter remains generic and domain-blind.**
4. **The interpreter remains pure.**
5. **No `eval`.**
6. **No macros are needed for the DSL.**
7. **Executable forms use the `:expr/*` boundary.**
8. **Malli structural validation remains separate from business rejection.**
9. **Invalid semantic bundles are defects, not business decisions.**
10. **`:first-failure` is explicit and rule order is semantic.**
11. **Derivation order is semantic.**
12. **Rule IDs must be unique within a bundle.**
13. **Derived names must be unique within a bundle.**
14. **Undefined/forward derived references remain invalid.**
15. **Accepted/rejected decisions include `:spec/ref`.**
16. **Specification identity uses ID + version + content hash.**
17. **The existing hash meaning must not change silently.**
18. **Events are data; event sourcing is not assumed.**
19. **No persistence or effect execution belongs in the semantic interpreter.**
20. **Keep the DSL small; prefer modeling over operator proliferation.**
21. **Keep ordinary Clojure data structures visible.**
22. **Add tests whenever language semantics change.**
23. **Update this README when architectural semantics change.**

---

## 77. Questions Codex should ask itself before changing code

For any proposed change:

```text
Is this domain meaning or generic machinery?
```

If domain meaning, it probably belongs in an EDN bundle.

```text
Is this malformed-input validation or business acceptability?
```

If malformed-input validation, Malli may be appropriate.
If business acceptability, it probably belongs in `:rules`.

```text
Is this a repeated/meaningful intermediate fact?
```

If yes, consider `:derive` before adding interpreter machinery.

```text
Does this change which rejection wins when several rules fail?
```

If yes, it changes semantics under `:first-failure`.

```text
Does this change the DSL language?
```

If yes, update interpreter + schema + tests + README together.

```text
Does this add an effect?
```

If yes, it almost certainly does not belong in `decider.dsl`.

```text
Does this add domain terminology to generic Clojure code?
```

If yes, reconsider the boundary.

```text
Does this create a second source of truth for a business rule?
```

If yes, avoid it unless explicitly required for a temporary experiment.

---

## 78. Desired style for future code

Prefer code that is:

- small;
- explicit;
- immutable;
- REPL-friendly;
- data-oriented;
- composed from ordinary functions;
- unsurprising to an experienced Clojure reader;
- clear about where effects occur;
- narrow in naming;
- free of unnecessary frameworks and abstraction layers.

Avoid cleverness whose primary benefit is fewer characters.

The primary optimization target is semantic clarity.

Before finishing a change, `bb verify` should be silent and green.
The lint and format configuration is deliberately unconfigured — no suppressed
warnings, no per-file exceptions — so a finding is a finding, and the right
response is to fix the code rather than to widen the configuration.

---

# Part XVIII — suggested future experiments, deliberately not implemented

These are reasonable directions for investigation, but they are not requirements of the current repository.

## 79. Stronger domain-specific generative properties

Add properties that test semantic invariants independently of individual example cases.

This is probably the most valuable next testing improvement.

---

## 80. `:all-failures` evaluation strategy

Investigate whether some use cases need all failing business rules rather than only the first.

If implemented, preserve `:first-failure` exactly and add a separately specified strategy.

---

## 81. Effective-dating/governance metadata

A real institutional system may need metadata such as:

```clojure
:spec/effective-from
:spec/effective-to
:spec/approved-by
:spec/source-requirements
```

These concerns should not be invented casually. They belong to a wider requirements/governance model rather than the minimum decision interpreter.

---

## 82. External requirement traceability

`:rule/id` could later link to a requirements graph, legislation, acceptance criteria, verification evidence, or institutional policy.

The current bundle intentionally does not solve that wider traceability system.

---

## 83. Static analysis of the DSL

Possible future checks include:

- operator type compatibility;
- unreachable rules;
- unused derived values;
- unused state fields;
- duplicate/redundant expressions;
- rule dependency visualization.

These may be valuable, but do not complicate the core interpreter until there is a demonstrated need.

---

# Part XIX — shortest possible mental model

If the rest of this README is forgotten, retain this:

```text
1. EDN says what the business decision means.

2. Malli says whether the supplied data and the EDN language are well formed.

3. Named derivations turn raw facts into useful decision facts.

4. Ordered business rules decide whether a valid request is allowed.

5. :first-failure makes rule order part of the semantics.

6. Accepted and rejected decisions are ordinary immutable data.

7. ID + version + hash identify the exact semantic bundle involved.

8. The Clojure interpreter knows the little language, not the business domain.

9. Effects, persistence, evolve, react, and event sourcing are outside this experiment.

10. Keep the language small and the business meaning visible.
```

The design is successful if, when somebody asks:

> Why did this business request succeed or fail?

we can locate the relevant semantic bundle, identify the exact version/hash, inspect the state and command supplied to it, and read the business rule that governed the answer—without first understanding a large amount of infrastructure machinery.
