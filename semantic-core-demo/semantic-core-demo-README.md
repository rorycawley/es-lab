# Semantic Core Demo

## What this project is

This project is a proof of concept for a **generic, pure, data-driven semantic engine** for event-sourced information systems.

The hypothesis being tested is:

> A large class of transactional information systems can share the same small piece of pure Clojure code, while their business meaning is supplied almost entirely as immutable semantic data.

The demo proves this using three unrelated domains:

- concert ticket reservation;
- land registration;
- corporate registration.

The same engine code runs all three.

The engine contains no ticket-specific, land-specific, or company-specific branching.

The domain differences live in three EDN semantic bundles:

```text
resources/bundles/
├── ticket.edn
├── land.edn
└── corporate.edn
```

The generic engine lives here:

```text
src/semantic_core/engine.clj
```

The project is intentionally small. It is not a production event store, message bus, HTTP service, or persistence implementation. It exists to answer one architectural question:

> **Can business rules, state evolution, finite-state machines, and process reactions be expressed as data and interpreted by one small set of pure functions?**

For these three examples, the answer is yes.

---

# 1. The architectural idea

The real application is treated as the **pure semantic core**.

Everything inside that core is one of two things:

```text
DATA
----
business rules
decision definitions
FSM definitions
state models
workflow definitions
commands
events
state
operator identifiers

PURE FUNCTIONS
--------------
evaluate
render
decide
evolve
transition
react
hydrate
evolve-process
```

There are no side effects in `engine.clj`.

It does not:

- access PostgreSQL;
- publish messages;
- call HTTP;
- read the clock;
- generate UUIDs;
- authenticate users;
- log;
- use an event-store client;
- call Stripe;
- call S3;
- send email.

The engine operates only on immutable values and returns immutable values.

Conceptually:

```text
semantic data
     +
current knowledge
     +
input
     |
     v
pure function
     |
     v
new values
```

This is the central design constraint.

---

# 2. Why this design exists

The design is inspired by a Rich Hickey-style definition of simplicity.

The goal is not to make the software look organised by adding layers, modules, interfaces, frameworks, or folders.

Those things can preserve boundaries, but they do not create simplicity.

The important work is **de-complecting** independent concerns.

In this demo:

```text
business rule definition
!= rule interpreter

lifecycle definition
!= lifecycle transition function

workflow definition
!= workflow interpreter

state
!= behaviour

business semantics
!= persistence

business semantics
!= messaging

business semantics
!= HTTP
```

Each concept is meant to be understandable on its own.

They are then combined using composition.

The engine is deliberately closer to:

```text
data -> function -> data -> function -> data
```

than to an object model containing mutable objects with repositories, callbacks, infrastructure services, and side effects attached to them.

---

# 3. The minimal semantic algebra

The prototype revolves around three main domain operations.

## `decide`

Conceptually:

```text
Command × State × Semantic Definition
                |
                v
             Decision
```

Meaning:

> Given a request, what is currently known, and the applicable business rules, what new facts may become true?

Example:

```text
ConfirmTicketSale
       +
current seat state
       +
ticket rules
       |
       v
     decide
       |
       +---- accepted -> TicketSold
       |
       +---- rejected -> reason
```

---

## `evolve`

Conceptually:

```text
State × Event × State Definition
            |
            v
          State'
```

Meaning:

> Given a fact, what is now known?

Example:

```text
seat status = held
       +
TicketSold
       |
       v
     evolve
       |
       v
seat status = sold
```

---

## `react`

Conceptually:

```text
Message × ProcessState × Workflow
                 |
                 v
              Reaction
```

Meaning:

> Given something that happened and what the process currently knows, what should happen next?

Example:

```text
PaymentConfirmed
       +
checkout process state
       +
checkout workflow
       |
       v
     react
       |
       +---- process event
       |
       +---- ConfirmTicketSale command
```

---

# 4. Project structure

```text
semantic-core-demo/
│
├── deps.edn
├── bb.edn
├── mise.toml
├── tests.edn
│
├── src/
│   └── semantic_core/
│       ├── engine.clj
│       ├── operators.clj
│       └── demo.clj
│
├── resources/
│   └── bundles/
│       ├── ticket.edn
│       ├── land.edn
│       └── corporate.edn
│
└── test/
    └── semantic_core/
        └── engine_test.clj
```

Each file has a deliberately narrow responsibility.

### `engine.clj`

Contains the generic pure interpreter.

It knows the structure of the semantic language, but no domain-specific facts.

### `operators.clj`

Contains the registered pure operations available to the semantic DSL.

### `demo.clj`

Loads each bundle and runs one scenario using the same engine.

This is orchestration for the demonstration, not domain logic.

### `*.edn`

Contain the actual business semantics for each domain.

### `engine_test.clj`

Checks the expression and state-update algebras, every registered operator, all configured FSM transitions, every business-rule rejection, successful execution in all three domains, and workflow reaction and evolution behavior.

### `bb.edn`

Provides the same task-oriented entry points used by the neighboring labs: `setup`, `demo`, `lint`, `fmt:check`, `fmt:fix`, `test`, `check`, and `all`.

### `deps.edn`

Pins Clojure as the sole production dependency. Its `:test` alias adds Kaocha and the test classpath, while `:demo` provides the demo entry point.

### `tests.edn`

Provides Kaocha's test discovery configuration.

### `mise.toml`

Pins the Java, Clojure CLI, Babashka, clj-kondo, and cljfmt toolchain to the same versions used by the neighboring labs.

---

# 5. The expression language

The semantic bundles need conditions and value references.

Rather than allowing arbitrary Clojure evaluation, the demo implements a deliberately tiny expression language.

The function is:

```clojure
(evaluate operators env expr)
```

The supported forms are:

```clojure
[:value x]

[:state path]

[:input path]

[:op operator-id arg1 arg2 ...]
```

---

## Literal values

```clojure
[:value :confirmed]
```

evaluates to:

```clojure
:confirmed
```

---

## Reading state

```clojure
[:state [:payment/status]]
```

reads:

```clojure
(get-in state [:payment/status])
```

---

## Reading input

The meaning of `:input` depends on the current operation.

Inside `decide`, the input is a command.

Inside `evolve`, the input is an event.

Inside `react`, the input is a message.

For example:

```clojure
[:input [:data :examiner-id]]
```

---

## Calling an operator

```clojure
[:op :core/=
 [:state [:payment/status]]
 [:value :confirmed]]
```

means:

```text
take the payment status from state
compare it with :confirmed
using the registered :core/= operator
```

The semantic definition stores an **operator identifier**, not executable code.

That identifier is resolved through the operator registry.

---

# 6. Operator registry

`operators.clj` currently contains:

```clojure
{:core/= =
 :core/not= not=
 :core/and ...
 :core/or ...
 :core/not not
 :core/< <
 :core/<= <=
 :core/> >
 :core/>= >=
 :core/contains? contains?}
```

This is an important architectural seam.

Semantic data may say:

```clojure
[:op :core/not= ...]
```

but does not know how `not=` is implemented.

The implementation is hidden behind a narrow semantic name.

The intended rule is:

> Every operator exposed to the semantic engine must be pure.

An operator must behave like:

```text
values -> value
```

It must not perform I/O or depend on hidden mutable state.

Future domain-specific operators might include:

```text
:land/parcels-overlap?
:company/name-permitted?
:ticket/hold-expired?
```

Those may contain substantial algorithms, but they should still be pure functions.

This lets the DSL remain small without trying to become a general-purpose programming language.

---

# 7. Rendering output templates

Business definitions must also be able to construct events and commands.

The function:

```clojure
(render operators env template)
```

walks maps, vectors, and sets recursively. Other values, including lists and arbitrary sequences, are returned unchanged.

Embedded semantic expressions are evaluated.

For example:

```clojure
{:event/type :ticket/sold
 :data
 {:seat
  [:input [:data :seat]]

  :customer
  [:input [:data :customer]]}}
```

given this command:

```clojure
{:command/type :ticket/confirm-sale
 :data
 {:seat "A-10"
  :customer "C-1"}}
```

becomes:

```clojure
{:event/type :ticket/sold
 :data
 {:seat "A-10"
  :customer "C-1"}}
```

This is how semantic definitions remain data while still producing domain-specific values.

---

# 8. Rules

A rule is one business proposition that must hold.

Example from the corporate bundle:

```clojure
{:rule/id :four-eyes

 :assert
 [:op :core/not=
  [:state [:examination/first-examiner]]
  [:input [:data :examiner-id]]]

 :reject
 {:code :four-eyes-violation}}
```

Meaning:

```text
The examiner trying to approve the application
must not be the examiner recorded for the first examination.
```

A Rule does **not** emit an event.

It answers only:

```text
PASS
or
FAIL
```

`rule-result` returns data such as:

```clojure
{:rule/id :four-eyes
 :result :pass
 :reject {:code :four-eyes-violation}}
```

This also starts to create decision evidence.

---

# 9. Decision definitions

A Decision Definition connects a command to:

- the rules that govern it;
- the events that should be emitted if all rules pass.

Example:

```clojure
{:decision/id :approve-application

 :on :company/approve-application

 :rules
 [:application-must-be-submitted
  :payment-must-be-confirmed
  :four-eyes]

 :emit
 [{:event/type :company/application-approved
   :data
   {:application-id
    [:input [:data :application-id]]

    :second-examiner
    [:input [:data :examiner-id]]}}]}
```

This is the data-driven form of:

```text
WHEN ApproveApplication

REQUIRE:
  application submitted
  payment confirmed
  four-eyes satisfied

THEN:
  record ApplicationApproved
```

The engine code does not know what approval means.

---

# 10. `decide`

Implementation:

```clojure
(decide operators bundle command state)
```

The algorithm is:

```text
1. Find Decision Definition matching command type.

2. Resolve each referenced Rule.

3. Evaluate every Rule against:
      current state
      command

4. If a rule fails:
      return rejection
      emit no events

5. If all rules pass:
      render configured event templates
      return accepted decision
```

Accepted result:

```clojure
{:status :accepted

 :events
 [...]

 :evidence
 {:decision/id ...
  :rules [...]}}
```

Rejected result:

```clojure
{:status :rejected

 :events []

 :reason
 {:code ...}

 :evidence
 {...}}
```

This is the core Aggregate decision operation in the demo.

---

# 11. Finite State Machines

An FSM definition is ordinary EDN.

Ticket example:

```clojure
{:fsm/id :ticket/seat-lifecycle
 :initial :available

 :transitions
 [{:from :available
   :on :ticket/seat-held
   :to :held}

  {:from :held
   :on :ticket/sold
   :to :sold}

  {:from :held
   :on :ticket/hold-expired
   :to :available}]}
```

The generic function is:

```clojure
(transition fsm current-state event)
```

Example:

```text
current state = :held
event         = :ticket/sold

        |
        v

transition

        |
        v

new state = :sold
```

The FSM contains lifecycle semantics.

`transition` contains only the generic mechanism.

---

# 12. State models

An FSM is not enough to represent all domain knowledge.

For example, a ticket also needs to know which customer owns the hold.

A State Model defines:

- initial state;
- which paths are controlled by FSMs;
- how other event data changes state.

Ticket example:

```clojure
{:initial
 {:seat/status :available
  :seat/customer nil}

 :fsm-paths
 {[:seat/status]
  :ticket/seat-lifecycle}

 :on-events
 [{:on :ticket/seat-held

   :updates
   [[:set
     [:seat/customer]
     [:input [:data :customer]]]]}

  {:on :ticket/hold-expired

   :updates
   [[:set
     [:seat/customer]
     [:value nil]]]}]}
```

The update language is deliberately tiny.

Currently:

```text
:set
:conj
:disj
```

The purpose is not to provide every possible data operation.

New update forms should be added only when real domain modelling demonstrates they are needed.

---

# 13. `evolve`

Implementation:

```clojure
(evolve operators bundle state event)
```

The algorithm is:

```text
1. Find ordinary state updates for the event.

2. Apply those updates.

3. Run the same event through every FSM referenced
   by the state model.

4. Return the new immutable state.
```

Example for corporate registration:

Historical events:

```text
ApplicationSubmitted
PaymentNoted
FirstExaminationCompleted
```

produce state approximately like:

```clojure
{:application/status :submitted
 :payment/status :confirmed
 :examination/first-examiner "E-1"}
```

Within the intended event-sourced architecture, that state is not authoritative storage.

It is derived knowledge.

The supplied event history is treated as the authoritative history by the pure semantic model. This repository can fold such a history, but it does not persist or govern an authoritative event stream.

---

# 14. `hydrate`

`hydrate` reconstructs current Aggregate knowledge from historical facts:

```clojure
(hydrate operators bundle events)
```

It is simply:

```text
initial state
     |
     v
evolve(event 1)
     |
     v
evolve(event 2)
     |
     v
evolve(event 3)
     |
     v
current state
```

In code it is essentially:

```clojure
(reduce evolve initial-state events)
```

This is the core event-sourcing relationship.

---

# 15. Workflows

A Workflow describes a stateful reaction.

Example from ticketing:

```clojure
{:workflow/id :ticket/checkout
 :workflow/version 1

 :initial
 {:status :awaiting-payment}

 :reactions
 [{:reaction/id :payment-confirmed

   :on :payment/confirmed

   :when
   [:op :core/=
    [:state [:status]]
    [:value :awaiting-payment]]

   :emit-events
   [{:event/type
     :ticket-process/payment-observed}]

   :emit-commands
   [{:command/type
     :ticket/confirm-sale

     :data
     {:seat
      [:input [:data :seat]]

      :customer
      [:input [:data :customer]]}}]}]

 ...}
```

Meaning:

```text
WHEN PaymentConfirmed is observed

AND checkout process is awaiting payment

THEN:
  record PaymentObserved in the process
  request ConfirmTicketSale
```

Again, the engine contains no knowledge of ticket checkout.

---

# 16. `react`

Implementation:

```clojure
(react operators bundle message process-state)
```

The algorithm is:

```text
1. Read the bundle workflow.

2. Find a reaction matching the incoming event/message type.

3. Evaluate its optional :when condition.

4. Render process-event templates.

5. Render command templates.

6. Return both as values.
```

Example result:

```clojure
{:events
 [{:event/type
   :ticket-process/payment-observed}]

 :commands
 [{:command/type
   :ticket/confirm-sale
   :data {...}}]

 :evidence
 {:workflow/id :ticket/checkout
  :reaction/id :payment-confirmed}}
```

The Process Manager does not call another Aggregate.

It emits a **Command value**.

Some outer runtime would later deliver that command.

---

# 17. `evolve-process`

Process Managers also need durable state.

Their own process events can be folded using:

```clojure
(evolve-process operators bundle state event)
```

For example:

```text
process state:
{:status :awaiting-payment}

        +

ticket-process/payment-observed

        |
        v

evolve-process

        |
        v

{:status :payment-confirmed}
```

This demonstrates the intended Process Manager model:

```text
react + evolve
```

The current demonstration runner does **not yet reconstruct a Process Manager from a history of process events**.

It calls `react` using the workflow's initial process state.

The test suite applies one process event for every bundle and verifies the resulting process state. It does not yet test reconstruction from a multi-event process history.

A production-quality demo should add:

```clojure
hydrate-process
```

and test a multi-message Process Manager lifecycle.

---

# 18. Ticket reservation bundle

The ticket bundle demonstrates:

### Business state

```text
seat status
customer owning the hold
```

### Rules

```text
seat must currently be held

customer trying to buy the seat
must own the hold
```

### FSM

```text
available
   |
   | SeatHeld
   v
 held
 /   \
|     |
|     | TicketSold
|     v
|    sold
|
| HoldExpired
v
available
```

### Decision

```text
ConfirmTicketSale
        |
        v
evaluate rules
        |
        v
TicketSold
```

### Workflow

```text
PaymentConfirmed
       |
       v
checkout workflow
       |
       v
ConfirmTicketSale
```

This proves the engine can represent a reservation/sale lifecycle.

---

# 19. Land registry bundle

The land bundle demonstrates a different domain using the same engine.

### Business state

```text
title status
registered proprietor
```

### Rules

```text
title must be active

transferor must be the currently
registered proprietor
```

### FSM

```text
unregistered
     |
     | TitleCreated
     v
   active
     |
     | TitleClosed
     v
   closed
```

### Decision

```text
RegisterTransfer
       |
       v
rules
       |
       v
TransferRegistered
```

### State evolution

`TransferRegistered` changes:

```text
:title/proprietor
```

from:

```text
P-1
```

to:

```text
P-2
```

### Workflow

```text
TransferApproved
       |
       v
registration workflow
       |
       v
RegisterTransfer
```

No land-specific code was added to `engine.clj`.

---

# 20. Corporate registry bundle

The corporate example demonstrates business invariants that are different again.

### Business state

```text
application lifecycle
payment status
first examiner
```

### Rules

```text
application must be submitted

payment must be confirmed

second examiner must differ
from first examiner
```

### FSM

```text
draft
  |
  | ApplicationSubmitted
  v
submitted
  |
  | ApplicationApproved
  v
approved
  |
  | CompanyIncorporated
  v
incorporated
```

### Decision

```text
ApproveApplication
       |
       v
application submitted?
payment confirmed?
four-eyes satisfied?
       |
       v
ApplicationApproved
```

### Workflow

```text
ApplicationApproved
       |
       v
incorporation workflow
       |
       v
CreateCompany
```

The test suite also changes the second examiner to the same examiner as the first and verifies:

```clojure
{:status :rejected
 :reason {:code :four-eyes-violation}}
```

This proves that the rules are not just documentation.

They directly control whether a new fact may be emitted.

Equivalent rejection paths are tested for every other configured rule in the ticket, land, and corporate bundles.

---

# 21. What the three examples prove

The important observation is not merely that all three programs work conceptually.

It is that:

```text
engine.clj
```

does not change between them.

The generic engine sees only structures such as:

```text
:rules
:decisions
:fsms
:state-model
:workflow
```

The bundles provide the meaning.

Therefore:

```text
Ticket system
Land registry
Corporate registry
```

share a computational form:

```text
current knowledge
      +
input
      +
semantic definition
      |
      v
pure interpretation
      |
      v
facts / commands / new knowledge
```

That is the architectural hypothesis being tested.

---

# 22. What this demo deliberately does not contain

This repository is a **semantic-core proof**, not a complete information system.

It does not yet implement:

```text
event persistence
optimistic concurrency
stream versions
PostgreSQL
outbox
inbox
RabbitMQ
HTTP
OIDC
authorization middleware
Malli validation
integration-event contracts
event envelopes
semantic provenance
content hashes
effective dating
bitemporal timestamps
semantic-bundle resolution
workflow migration
schema upcasting
snapshots
projections
logging
tracing
metrics
```

Those belong outside the pure semantic core or are later refinements of semantic-definition governance.

Do not add them to `engine.clj` merely because a production system requires them.

---

# 23. Important demo shortcuts

An LLM extending this repository should understand the following shortcuts.

## Semantic versions are descriptive only

Bundles contain:

```clojure
:bundle/version
:workflow/version
:operator-set
:interpreter
```

but the prototype does not yet resolve versions, validate them, hash definitions, or enforce immutability.

That is future work.

---

## There is no schema validation

A malformed bundle may fail poorly.

For example, a Decision Definition referencing a missing Rule is not currently rejected during bundle loading.

Production design should introduce an **external bundle-validation step** before execution.

That validator can use Malli or another schema system.

The pure engine itself should still receive already-valid data.

---

## Unknown FSM transitions are currently no-ops

`transition` returns the current state when no transition matches.

This is a demo policy:

```text
unknown transition -> unchanged state
```

That may not be suitable universally.

A later design needs to distinguish explicitly between:

```text
event irrelevant to this FSM
```

and:

```text
event claims an invalid lifecycle transition
```

Do not silently assume these are the same semantic case.

---

## `react` selects only the first matching reaction

The current implementation uses `some`.

Therefore only one Workflow reaction is selected for a message.

A future semantic model must decide deliberately whether:

```text
exactly one reaction
zero or one reaction
many reactions
```

is valid.

Do not change this casually; it is a semantic design decision.

---

## Rules fail fast for the decision outcome but all configured rules are evaluated

`decide` creates results for every rule, then identifies the first failing result for the rejection reason.

This supports decision evidence.

A future version may distinguish:

```text
all rule evidence
```

from:

```text
primary rejection reason
```

more explicitly.

---

## The Process Manager example is only one step deep

The demo does not yet demonstrate:

```text
message 1
 -> process event
 -> process state 1

message 2
 -> process event
 -> process state 2

message 3
 -> command
```

That is an important next proof.

---

# 24. Architectural laws for extending the demo

When changing this repository, preserve these constraints unless there is a strong reason not to.

### 1. Domain-specific meaning belongs in bundles or pure domain operators

Do not add:

```clojure
(case domain
  :ticket ...
  :land ...
  :corporate ...)
```

to the engine.

That would invalidate the experiment.

---

### 2. The engine remains pure

Do not put:

```text
database access
HTTP
message publishing
current time
randomness
logging
```

inside the semantic functions.

Pass facts as values instead.

---

### 3. The DSL stays deliberately small

Do not add arbitrary:

```text
eval
embedded Clojure
scripts
callbacks from configuration
```

The DSL should be a closed semantic language.

Complex calculations should become named pure operators.

---

### 4. Historical events are facts

A production event-sourced implementation must never reconstruct history by re-running old commands through current rules.

Historical replay is:

```text
events -> evolve -> state
```

not:

```text
old commands -> current decide -> replacement history
```

---

### 5. State is derived knowledge

The event history is authoritative.

State is the value needed to make the next decision.

---

### 6. Rules and FSMs have different jobs

A Rule answers:

```text
may this decision be accepted?
```

An FSM answers:

```text
what lifecycle state follows from this fact?
```

Do not collapse them.

---

### 7. Workflows coordinate; Aggregates own domain facts

A workflow emits Commands.

A target Aggregate decides whether those Commands are accepted.

The workflow must not create another Aggregate's authoritative Domain Event directly.

---

# 25. How the demo would fit inside a real event-sourced system

The pure engine would sit inside an imperative shell.

A write path might eventually look like:

```text
Command arrives
      |
      v
validate schema
authenticate / authorize
      |
      v
load event stream from PostgreSQL
      |
      v
hydrate
      |
      v
resolve semantic bundle
      |
      v
decide
      |
      +---- rejected
      |
      v
new domain events
      |
      v
validate event schema
      |
      v
PostgreSQL transaction
      |
      +-- append events
      +-- decision evidence
      +-- outbox rows
      |
      v
commit
```

Only this part is the semantic core:

```text
history -> hydrate

bundle + command + state -> decide

state + event -> evolve
```

Everything else is machinery.

---

# 26. Future semantic provenance

The larger architecture intends every newly admitted fact to identify the exact semantic definition under which it was accepted.

Eventually an event envelope may refer to something like:

```clojure
{:semantic
 {:bundle/id      :corporate
  :bundle/version 19
  :bundle/hash    "sha256:..."

  :operator-set/version 5
  :interpreter/version 3}}
```

This demo already contains placeholders for some of those concepts, but does not implement the provenance system.

A production system should treat published semantic definitions as:

```text
immutable
versioned
schema validated
content hashed
effective dated
approved
tested
permanently retained where history depends on them
```

---

# 27. How to run

The project follows the task convention used by the neighboring labs:

```bash
bb demo
bb test
bb lint
bb fmt:check
bb all
```

Run `bb fmt:fix` to apply the formatting expected by `bb fmt:check`. `bb all`
trusts the local mise configuration, installs its pinned tools, then runs static
checks, tests, and the demo.

The principal underlying Clojure commands are:

```bash
clojure -M:demo
clojure -M:test
```

The test alias runs Kaocha using `tests.edn`. The static checks run clj-kondo
and cljfmt with the versions pinned in `mise.toml`.

The code is also directly compatible with Babashka:

```bash
bb --classpath src:resources -m semantic-core.demo
bb --classpath src:resources:test \
  -m semantic-core.engine-test
```

The suite verifies:

```text
the complete expression and update algebras
all registered operators
bundle references and every configured FSM transition
successful ticket, land, and corporate decisions
every configured business-rule rejection
aggregate and workflow no-op behavior
workflow guards and process-state evolution
explicit errors for unknown commands, operators, and updates
```

Both the Clojure CLI and direct Babashka test commands have been executed successfully against the current suite.

---

# 28. Recommended next proof

Do **not** immediately add Postgres, RabbitMQ, or HTTP.

The most valuable next experiment is to stress the semantic model itself.

Add:

1. a genuinely multi-step Process Manager;
2. multiple FSMs in one Aggregate;
3. a domain-specific pure operator;
4. a second Decision Definition in every bundle;
5. invalid semantic bundles and a separate validation layer;
6. semantic version resolution;
7. property tests for replay and determinism.

The key question remains:

> **Can each new business requirement be expressed cleanly by adding semantic data or a small pure operator, without making the generic engine know which domain it is running?**

If the answer continues to be yes, the architecture is gaining evidence.

If the engine begins accumulating domain flags, special cases, arbitrary scripting, or semantic exceptions, the abstraction should be reconsidered.

---

# 29. Mental model for an LLM

When working on this repository, think in this order:

```text
1. What business fact or rule are we trying to express?

2. Is it:
      a Rule?
      a Decision Definition?
      an FSM transition?
      a State Model update?
      a Workflow reaction?
      a pure Operator?

3. Can it be represented as immutable data?

4. If not, can the complex calculation be represented
   as one named pure operator?

5. Only change engine.clj if the missing capability is
   genuinely generic across domains.
```

Do not begin with:

```text
Which class should I add?
Which service should call which service?
Which framework abstraction should own this?
```

Begin with:

```text
What does the business mean?
What values express that meaning?
What pure transformation is required?
```

---

# 30. Summary

This repository tests a very specific architectural proposition:

```text
                    ONE SMALL ENGINE

                           +

              MANY SEMANTIC DATA BUNDLES

                           =

              DIFFERENT INFORMATION SYSTEMS
```

The engine supplies generic pure interpretation.

The bundle supplies domain meaning.

For this proof:

```text
ticket semantics
land-registry semantics
corporate-registry semantics
```

all execute through the same functions:

```clojure
evaluate
render
decide
evolve
transition
hydrate
react
evolve-process
```

The long-term objective is not a universal framework that hides all complexity.

It is narrower:

> **Keep the executable business meaning explicit, immutable, data-oriented, pure, independently understandable, and composable; keep generic machinery outside it.**
