# Test Strategy for a Use-Case 3.0, Event-Sourced National Corporate Registry

## Strategic position

The central recommendation is to make **Use-Case 3.0 test cases the spine of the test strategy**, and to make every other kind of test serve those test cases.

That is an unusually good fit for the architecture you described.

Use-Case 3.0 explicitly treats a use-case slice as an end-to-end slice of value containing the relevant path through a use case together with the design, code, and tests needed to implement and verify it. A slice is expected to be testable, and the guide says the test cases define what successful completion of the slice means. It also explicitly allows non-functional slices for concerns such as performance and load. citeturn4view0turn5view0turn5view1

Kent Beck's testing philosophy fits this very naturally. His Test Desiderata say that tests should primarily be coupled to **behavior**, not implementation structure, while being as isolated, fast, deterministic, readable, specific, writable, automated, predictive, and confidence-inspiring as their role allows. He is explicit that these attributes involve tradeoffs: programmer tests and acceptance tests occupy different points in that space. citeturn12view0turn12view1

For your system, that leads to this model:

> **Requirements describe behavior. Use-Case Test Cases define what proves that behavior. Fast programmer tests drive the implementation. Public-boundary tests prove modules. Contract tests prove interfaces. System acceptance tests prove composition. Reliability tests prove failure semantics. Performance tests prove that the delivered behavior remains fast. Production telemetry continuously checks whether those claims remain true.**

I would **not** organize this around a prescriptive "70% unit / 20% integration / 10% end-to-end" pyramid. There is no useful percentage that can be derived from your architecture. Instead, organize tests according to **what claim they prove and how quickly they provide feedback**. Beck's own framework explicitly recognizes that different tests make different tradeoffs rather than there being one ideal type of test. citeturn12view0turn12view1

The resulting hierarchy is:

| Test family | Primary question |
|---|---|
| Domain/programmer tests | Is this business rule correct? |
| Module behavior tests | Does this module behave correctly through its public interface? |
| Adapter contract tests | Does this adapter faithfully implement its port? |
| API/message contract tests | Can independently changing producers and consumers still communicate? |
| Event-sourcing/CQRS tests | Can historical truth be written, replayed, projected, and evolved correctly? |
| Distributed reliability tests | Does correctness survive duplicates, concurrency, retries, crashes, and partitions? |
| Use-Case acceptance tests | Has this requirement actually been delivered? |
| Performance tests | Is the delivered behavior fast enough under the required conditions? |
| Production verification | Is it still correct and fast for real users? |

The most important distinction throughout the strategy is:

**A programmer test helps you build the implementation. A Use-Case test case proves the requirement.**

Those should be connected, but they should not be confused.

## Requirements traceability and proof of delivery

### Make the Use-Case test case the stable traceability unit

Use-Case 3.0 is unusually emphatic about testing. Its definitive guide says that test cases provide the mechanism for completing and verifying requirements, can be specified before implementation begins, and define what successful implementation of a use-case slice means. It further says that test cases remain useful throughout the system's lifetime for regression and other quality checks. citeturn5view1

That suggests this traceability graph:

```text
System / Stakeholder Requirement
             │
             ▼
          Use Case
             │
             ▼
      Flow / Constraint
             │
             ▼
       Use-Case Slice
             │
             ▼
     Use-Case Test Case
             │
        ┌────┴─────┐
        ▼          ▼
 authoritative   supporting
 verification      tests
        │
        ▼
 CI execution evidence
        │
        ▼
 exact release artifact / deployment manifest
```

Do **not** make your primary traceability relationship:

```text
Requirement → clojure.test function
```

That would eventually make your requirements model dependent on implementation structure.

Instead:

```text
Requirement → Use-Case Test Case
Use-Case Test Case → executable verification
```

The Clojure functions executing that verification can change freely.

This is an important application of Beck's structure-insensitivity principle: refactoring your namespaces, aggregate implementation, adapters, internal event processing, or function decomposition should not require changing the requirement model or the requirement-level test case as long as externally observable behavior has not changed. citeturn12view0

### Give everything stable identities

I would establish IDs such as:

```text
UC-COMPANY-REGISTRATION

UC-COMPANY-REGISTRATION/S01
UC-COMPANY-REGISTRATION/S02

UC-COMPANY-REGISTRATION/S01/TC01
UC-COMPANY-REGISTRATION/S01/TC02
UC-COMPANY-REGISTRATION/S01/TC03
```

The names remain human-readable:

```text
UC-COMPANY-REGISTRATION/S01
Register a company through the basic successful path

UC-COMPANY-REGISTRATION/S01/TC01
Valid application with all mandatory information
```

The IDs are permanent; wording can improve without destroying historical traceability.

Each Use-Case Test Case should carry, at minimum:

| Field | Purpose |
|---|---|
| Test Case ID | Permanent identity |
| Use Case ID | Parent requirement |
| Slice ID | Increment being verified |
| Flow/path references | Basic/alternate/failure flows exercised |
| Stakeholder goal | Why the test exists |
| Preconditions | Required starting state |
| Inputs/actions | Actor behavior |
| Expected observable outcome | What proves success/failure |
| Business postconditions | Required resulting state |
| Applicable system-wide requirements | Performance, security, audit, accessibility, etc. |
| Verification method | API, UI, event, composed, exploratory, etc. |
| Automation implementation IDs | Executable implementations |
| Lifecycle state | Idea / executable / automated etc. |

This closely matches Use-Case 3.0's own progression from an initial test idea, through a chosen scenario and explicit variables, to a fully scripted or automated test. citeturn5view0turn6view3

### Every flow needs test coverage

Use-Case 3.0 states that the narratives are collections of flows the system must support and that each described flow needs at least one test case. It also describes paths through those flows as the material from which test cases and slices are formed. citeturn5view0turn5view1

That gives you a much more meaningful coverage measure than source-line coverage:

\[
\text{Requirement Verification Coverage}
=
\frac{\text{verified in-scope requirement nodes}}
{\text{all in-scope requirement nodes}}
\]

For a release, that should be **100% for the requirements you have committed to deliver**.

The denominator should include:

```text
Use Cases
  → release slices
    → included flows / paths
      → applicable constraints
        → system-wide requirements
```

A CI job should detect:

```text
slice with no test case
flow claimed as implemented with no test case
test case with no requirement/slice
test case referencing a deleted requirement
system-wide requirement with no verification
release slice whose test cases have not all passed
```

This is the coverage gate that proves functionality has been delivered.

Source coverage can still be useful as an investigative tool, but it is not proof that requirements work. Beck specifically identifies tests written merely to obtain coverage rather than to assert behavior as a TDD mistake. citeturn12view2

### Define "Verified" mechanically

Use-Case 3.0 gives a slice a lifecycle ending in **Verified**, at which point it is done and ready for release. citeturn5view3

Make that state machine executable.

For a slice \(S\):

\[
Verified(S)
=
\bigwedge_{t \in TestCases(S)} Verified(t)
\]

For each test case:

\[
Verified(t)
=
\bigwedge_{e \in RequiredEvidence(t)} Pass(e)
\]

And importantly:

\[
Pass(e)
\]

must refer to the **same releasable deployment manifest**.

So CI should produce an evidence record approximately like:

```clojure
{:use-case/id       "UC-COMPANY-REGISTRATION"
 :slice/id          "UC-COMPANY-REGISTRATION/S01"
 :test-case/id      "UC-COMPANY-REGISTRATION/S01/TC01"

 :verification/type :system-acceptance
 :test/id           "register-company-basic-path"

 :deployment
 {:git-sha          "..."
  :artifact-digest  "sha256:..."
  :frontend-digest  "sha256:..."
  :backend-digest   "sha256:..."}

 :result             :pass
 :duration-ms        817
 :executed-at        #inst "..."}
```

That turns your test suite into **release evidence**, not simply a collection of green check marks.

It also gives you an auditable answer to:

> "Show me the evidence that every requirement in release R was delivered by exactly the software we deployed."

### Support composed proof

Not every Use-Case Test Case has to become one enormous browser test.

For example, a human-facing use-case test might be verified compositionally by:

```text
UI behavior test
       +
frontend/API contract
       +
backend public-behavior test
       +
API implementation contract
       =
requirement evidence
```

For the highest-risk journeys you can additionally require a real end-to-end browser verification.

This exploits the **composability** Beck values while avoiding a gigantic slow end-to-end suite. citeturn12view0turn1search4

The evidence model should explicitly say whether a Test Case uses:

```text
:verification :direct-end-to-end
```

or:

```text
:verification :composed
:requires #{:ui-behavior
            :api-contract
            :backend-behavior}
```

That is far stronger than pretending that every unit test independently proves a requirement.

## Test portfolio aligned to your architecture

Your architecture creates unusually good test seams. You should exploit them aggressively.

### The public module interface is the main behavioral test boundary

You have already established a highly valuable invariant:

> A module can only be used through commands, queries, and integration events.

Treat that as the normal boundary for module tests.

A module behavior test therefore says:

```text
Given a module-visible starting condition
When I submit public Command C
Then I observe public Result R
And subsequently public Query Q returns X
And integration event E is published if the contract requires it
```

It must **not** say:

```text
Then repository/save-company! was called once
Then handler X called service Y
Then namespace Z invoked helper A
```

Those tests describe implementation structure rather than behavior. Beck specifically calls out excessive strict mocking as a source of structure sensitivity. citeturn12view1

Your default testing policy should consequently be:

> **Do not mock your own module internals.**

Test your module from its stable public interface.

Use doubles at **ports**, because those are deliberately designed seams.

### Functional core

The functional core should contain the majority of the fastest tests.

For event-sourced aggregates, Microsoft now explicitly recommends the classic style:

```text
Given previous events
When a command is handled
Then these new events are emitted
```

because it exercises business logic without requiring databases, queues, or projections. Microsoft also notes that separate integration testing is still needed for projections, idempotency, and schema evolution. citeturn13view0

In Clojure, that naturally becomes something conceptually like:

```clojure
(defn execute
  [history command]
  (let [state (reduce domain/evolve domain/initial-state history)]
    (domain/decide state command)))
```

A test then remains entirely value-oriented:

```clojure
(deftest registration-is-rejected-when-domain-rule-is-violated
  (is (= {:rejection :some-domain-rule}
         (execute history command))))
```

No clock, database, HTTP server, broker, thread scheduler, UUID generator, or global state should be involved unless the domain rule itself genuinely depends upon that value.

When time or IDs matter, pass them in.

Kent explicitly recommends supplying clocks and randomness to deterministic unit tests rather than allowing those dependencies to make the tests nondeterministic. citeturn12view1

### Generative testing should be important in this Clojure system

Clojure's official `test.check` library is specifically designed for property-based testing: instead of enumerating only individual inputs and expected results, you define properties and generate many valid inputs. Failed runs retain a seed that can be used to reproduce the case. Clojure's official Spec documentation also supports generative checking of function arguments, return values, and relationships between them. citeturn7search0turn7search3turn7search4

This is particularly valuable for your functional core.

Examples of properties worth looking for include:

```text
No legal command produces an event violating a domain invariant.

Replaying the same history always produces the same state.

A rejected command produces no state-changing events.

All historical serialized events can still be evolved.

A projection rebuilt from its event history is equivalent
to one maintained incrementally.

Processing an already processed integration event does not
produce additional business effects.

Ordering operations that are defined as commutative does not
change the resulting business state.
```

The exact properties must come from your domain model rather than being invented by the testing framework.

This combination is powerful:

```text
Use-Case examples
        +
domain examples
        +
properties
```

Examples prove known business scenarios; properties search for business scenarios you did not think to enumerate.

### Ports and adapters

For every port with more than one implementation, create a **port contract suite**.

Conceptually:

```text
CompanyRepositoryContract
EventStoreContract
ClockContract
IdentifierGeneratorContract
MessagePublisherContract
DocumentStoreContract
...
```

Run the same behavior against:

```text
in-memory/fake implementation
production implementation
```

That prevents a common problem in ports-and-adapters systems: a fake that is convenient but behaves differently from production.

Your shell tests can then be fast because they use verified fakes, while adapter integration tests independently establish that the real PostgreSQL/event-store/broker/etc. adapter honors the same semantics.

### Public API and event contracts

Because you are API-first, the HTTP interface should have a machine-readable contract and CI should reject implementation drift from it. OpenAPI exists specifically to provide a language-independent description of an HTTP interface understandable without inspecting its implementation. citeturn13view5

The same reasoning applies to integration events. AsyncAPI provides a machine-readable description for message-driven APIs and is protocol-independent. citeturn13view6

Your compatibility checks should therefore cover:

```text
HTTP request shape
HTTP response shape
error representation
required/optional fields
enum compatibility
API version compatibility

integration-event envelope
event type
event version
payload schema
correlation identifier
causation identifier
producer expectations
consumer expectations
```

Schema compatibility is necessary but not always sufficient. Where semantic assumptions matter, consumer/provider contract examples can complement schemas; Pact, for example, distinguishes schema specifications from executable consumer contracts containing concrete interactions. citeturn10search2turn10search6

### Architecture conformance tests

There is one deliberate exception to "structure-insensitive tests":

**architecture rules.**

You explicitly require:

```text
Module A cannot reach into Module B internals.
Modules cannot depend on another module's adapters.
Only commands, queries and integration events cross boundaries.
Domain code cannot depend on infrastructure.
Imperative shells cannot leak into functional cores.
```

Those are architectural requirements.

Therefore create a small suite of deliberately structure-sensitive tests/static checks that inspect namespace/module dependencies and fail forbidden edges.

Keep these clearly separated from behavioral tests:

```text
test/behavior/...
test/contracts/...
test/architecture/...
```

There is no contradiction with Beck here. A business test accidentally coupled to class/function structure is brittle; an architecture test deliberately testing an architectural constraint is doing exactly the job it was written for.

A useful rule is:

> **Only four things are allowed to intentionally couple tests to structure: published contracts, durable event schemas, architecture boundaries, and explicit persistence/infrastructure contracts. Everything else should be tested behaviorally.**

## Event sourcing, CQRS, inbox/outbox, and distributed correctness

This is the part of the strategy where a conventional CRUD-oriented test suite will be inadequate.

Microsoft specifically warns that event-sourced systems require testing beyond aggregate command logic, including projections, idempotency, and event/schema evolution. citeturn13view0

### Event-sourced aggregate tests

For every command:

```text
Given historical events
When command
Then emitted events OR domain rejection
```

Exercise:

```text
no history
minimal valid history
normal historical states
boundary states
already-completed states
historically unusual but valid states
invalid command
concurrent-version conflict where relevant
```

Assertions should normally concern:

```text
emitted domain events
rejection
public command result
domain invariant
```

not the implementation mechanism that produced those events.

### Event replay is a permanent compatibility suite

An event in an event-sourced system is different from an ordinary internal data structure.

Once persisted in production it becomes part of your durable history.

Consequently maintain a corpus of historical event representations covering **every production event version**.

On every relevant build, verify:

```text
deserialize every historical version
apply any required upcasting/evolution
replay it into the current domain model
produce a legal current state
```

Do not merely test events generated by today's code.

Otherwise a refactor can produce a perfectly green modern suite while making five-year-old companies impossible to reconstruct.

This is particularly important for a registry whose intended life is likely to be measured in decades.

### Projection tests

CQRS gives read models different semantics from the write side. Microsoft's CQRS guidance explicitly notes that separate write and read models introduce eventual consistency: read-model updates can lag behind event generation. citeturn13view1

For each projection test:

```text
Given events E1 ... En
When projected
Then read model R
```

Add two especially important properties:

\[
Projection(E_1 \ldots E_n)
=
Rebuild(E_1 \ldots E_n)
\]

and, where duplicate delivery is possible:

\[
Projection(E, E)
=
Projection(E)
\]

or whatever equivalent idempotency rule your consumer contract defines.

Then periodically rebuild projections from canonical event history and compare them with continuously maintained projections.

That catches drift which example-only projection tests can miss.

### Inbox tests

Your inbox implementation deserves its own executable contract.

Test at least:

| Scenario | Required outcome |
|---|---|
| Message arrives once | Business effect happens |
| Same message arrives twice | Business effect is not duplicated |
| Same message arrives concurrently | Still not duplicated |
| Handler fails before transaction commit | Message can safely retry |
| Handler succeeds but acknowledgment fails | Retry remains harmless |
| Process dies during handling | Recovery reaches a valid state |
| Invalid/poison message | Defined failure policy occurs |

The key assertion is not merely "inbox row exists."

The business invariant is:

```text
Repeated delivery cannot create repeated externally observable business effects.
```

### Outbox tests

AWS describes the transactional outbox specifically as protection against the dual-write problem between durable database state and message publication, and notes that duplicate messages can still occur and therefore consumers should be idempotent. AWS also emphasizes preserving notification order where ordering matters. citeturn13view2

Therefore test the **failure windows**, not merely the happy path:

```text
before transaction begins

after domain event written
but before transaction commit

after outbox row written
but before transaction commit

after transaction commit
but before relay sees row

after broker publish
but before outbox record is marked published

during retry

during process restart
```

Your expected properties should be:

```text
rolled-back transaction → no externally observable event

committed transaction → event is eventually publishable

relay crash → event is not permanently lost

duplicate publish → consumer remains correct

required per-aggregate ordering → preserved
```

These are far more valuable than a test asserting that an `insert-outbox!` function was called.

### Concurrency tests

Event sourcing often makes optimistic concurrency explicit.

Test concurrent command histories where two writers load the same expected version:

```text
Writer A: expected revision 7
Writer B: expected revision 7
```

If A wins, B must observe whatever conflict semantics your public command contract promises.

Also test business-level invariants concurrently.

Do not only test database primitives.

For a registry, those invariants will come from your real requirements—for example, uniqueness or state-transition constraints **where the legislation/domain model actually requires them**.

### Fault testing

For important distributed invariants, add generative/system tests which deliberately introduce:

```text
process termination
process pause
network failures
timeouts
duplicate messages
reordered messages where the transport allows it
slow dependencies
dependency unavailability
node restart
```

Jepsen's approach is instructive here: generate concurrent operations, record their history, introduce faults through a "nemesis," and then check the resulting history against a correctness model. citeturn11search1turn11search11

You do not need to turn every registry test into a Jepsen test.

Use Jepsen-style checking where you make a meaningful distributed consistency claim whose violation could corrupt registry state.

A very good division is:

```text
test.check
    domain-value combinations and sequential properties

integration fault tests
    inbox/outbox/retry/crash boundaries

Jepsen/model-based workload tests
    concurrency + distributed consistency invariants
```

Since Jepsen itself is Clojure-based, this is also one of the rare cases where your technology choice makes sophisticated distributed-system testing unusually accessible. citeturn11search1

### Never solve eventual-consistency tests with arbitrary sleeps

Avoid:

```clojure
(Thread/sleep 5000)
(is (= expected (query ...)))
```

Instead:

```text
command
↓
poll the public query
↓
until:
    expected condition appears
or:
    the defined consistency deadline expires
```

The timeout should come from the actual visibility requirement.

That makes the test both less flaky and more meaningful:

```text
projection became visible after 182 ms
```

rather than merely:

```text
it happened to be visible after sleeping five seconds
```

That visibility duration should itself become a performance measurement.

## Performance as an executable requirement

Your statement that the system **must always be fast for the user** should become a first-class part of the requirements/test model rather than a separate performance-testing activity near release.

Use-Case 3.0 explicitly supports constraints such as quality and performance in use-case descriptions and explicitly allows additional non-functional slices for performance or load requirements. citeturn5view0turn6view0turn6view1

### Replace "fast" with observable SLIs and SLOs

Google's SRE guidance defines an SLI as a quantitative measure of service behavior and an SLO as its target. Latency, error rate, throughput, availability, and durability are common service indicators; client-side latency is often closer to what users actually experience than server-side latency. citeturn13view3

For every important registry task, define something like:

```text
Task:
    Find a registered company

Load profile:
    <specified representative load>

User-visible latency:
    p50 < ...
    p95 < ...
    p99 < ...

Backend query latency:
    p95 < ...

Error rate:
    < ...

Data freshness:
    < ...

Projection visibility after relevant write:
    p99 < ...

Availability target:
    < ...
```

I deliberately would **not invent those numbers technically**.

They are requirements and should come from:

```text
stakeholder expectations
legal/contractual commitments
expected workload
observed user behavior
capacity economics
```

Once agreed, they become Use-Case/system-wide requirements and therefore become testable.

### Measure tails, not just averages

Google's SRE guidance specifically warns that averages can hide very slow requests and recommends examining tail latency such as high-percentile response times. It also recommends distinguishing successful-request latency from failed-request latency. citeturn13view4

So:

```text
average = 80 ms
```

is not enough.

A service where:

```text
99% = 40 ms
1%  = 4 seconds
```

may still be an unpleasant system to use.

For a national registry, I would therefore make p95 and p99 prominent, with the exact percentile tied to the business criticality and traffic profile.

### CQRS introduces another user-facing latency

Because your command and query sides are asynchronous, request latency is not your entire user experience.

There is also:

\[
L_{visibility}
=
t_{\text{query reflects command}}
-
t_{\text{command accepted}}
\]

This is an inference from the CQRS consistency model: Microsoft notes that CQRS read models can lag behind event creation, while Google's SRE guidance says the most useful latency indicator is the one closest to what the user experiences. citeturn13view1turn13view3

For flows such as:

```text
submit change
→ success
→ redirect to company record
→ observe change
```

the user does not care that:

```text
POST /change-address = 38 ms
```

if the query side takes four seconds to show their update.

Therefore test:

```text
command latency
event publication latency
projection latency
query latency
end-to-end visible-result latency
```

### Build a performance feedback ladder

Performance testing should have multiple frequencies rather than one huge load test.

Grafana's official k6 guidance similarly distinguishes quick continuous performance validation from larger load tests, noting that large performance tests are often inappropriate for every CI run and can instead be scheduled or run in dedicated pre-release environments. citeturn9search5turn9search22

I would use:

| Frequency | Performance check |
|---|---|
| Developer | Benchmark a specifically affected hot path when relevant |
| Every PR | Small repeatable performance smoke for affected critical tasks |
| Main branch | Controlled comparison against previous baseline |
| Nightly | Representative average-load tests |
| Scheduled | Spike, stress and longer-duration tests |
| Release candidate | Full required SLO workload in production-like infrastructure |
| Production | Continuous real-user/service SLIs and synthetic task monitoring |

The PR check is there to detect:

```text
this change made a query 4× slower
```

not to prove maximum national capacity.

The controlled load environment is where you prove the actual SLO.

This distinction preserves Beck's fast-feedback principle while still making the complete test portfolio predictive of production. citeturn12view0

### Trace the entire task

OpenTelemetry defines a distributed trace as the events belonging to one logical operation across process and network boundaries. citeturn9search1

Instrument critical use-case tasks so a trace can show:

```text
browser action
  ↓
task endpoint
  ↓
command dispatch
  ↓
event-store append
  ↓
outbox
  ↓
broker
  ↓
consumer
  ↓
projection
  ↓
query
  ↓
visible UI update
```

Use correlation and causation identifiers throughout.

This means a failing performance test can tell you:

```text
p99 exceeded requirement
because:
  20 ms API
  14 ms command handling
  11 ms event store
  812 ms outbox publication
  37 ms projection
```

instead of merely saying:

```text
test timed out
```

That is the performance equivalent of Beck's **specific** test desideratum.

## Developer workflow and delivery pipeline

Kent Beck's recent description of canonical TDD begins with a **test list**: enumerate behavioral scenarios, turn exactly one into a runnable test, make it pass, optionally refactor, and repeat. He explicitly separates interface decisions from implementation decisions and advises adding newly discovered scenarios to the test list. citeturn12view2

Use-Case 3.0 already gives you a rich source for that list.

The workflow for every slice should therefore be:

```text
Use-Case Slice
      │
      ▼
Use-Case Test Cases
      │
      ▼
developer Test List
      │
      ▼
one failing programmer/module test
      │
      ▼
minimal behavior
      │
      ▼
green
      │
      ▼
refactor
      │
      ▼
next test
      │
      ▼
slice acceptance
      │
      ▼
Verified
```

That is probably the strongest connection between your requirements method and Beck-style TDD.

### Inner loop

The developer's normal feedback loop should contain:

```text
focused domain/programmer test
changed namespace tests
changed module tests
```

This should run in seconds, ideally with the test currently being driven effectively instantaneous.

Your functional core gives you an enormous advantage here.

No Docker.

No broker.

No database.

No HTTP server.

No global test fixture.

No external process.

### Local fast suite

Regularly run:

```text
all domain tests
all module behavior tests
all architecture-boundary tests
generative tests at developer trial counts
```

This suite must be deterministic and aggressively fast.

Kent says frequently run programmer tests gain much of their value from precisely this inexpensive feedback. citeturn12view1

### Pull request

Every PR should run, in parallel where possible:

| Gate | Required |
|---|---|
| Fast domain tests | Yes |
| Module public-interface behavior | Yes |
| Architecture boundary rules | Yes |
| Affected adapter contracts | Yes |
| Event-store/infrastructure contracts | Yes |
| OpenAPI compatibility | Yes |
| Integration-event compatibility | Yes |
| Relevant Use-Case Test Cases | Yes |
| Historical event compatibility | Yes |
| Short generative suite | Yes |
| Performance regression smoke | For affected critical paths |

Because the fast/core suite is expected to remain cheap, I would run the entire fast suite rather than relying exclusively on change-impact analysis.

Use change-impact analysis primarily to decide which expensive suites need running.

### Main branch and nightly

The larger asynchronous suites should contain:

```text
full Use-Case regression

browser integration journeys

large property-based runs

event-history replay corpus

projection rebuild comparison

inbox duplicate/concurrency testing

outbox crash-window testing

multi-process restart/fault scenarios

representative load

long-running performance tests

mixed-version compatibility where deployments overlap versions
```

Store failing `test.check` seeds so a random failure becomes a permanently reproducible regression. The official `test.check` API exposes seeds specifically to permit rerunning generated failures. citeturn7search3

### Release candidate

A release candidate should not be releasable because "CI is green."

It should be releasable because:

```text
all committed Use-Case Slices are Verified

all associated Use-Case Test Cases have evidence

all system-wide requirements applicable to the release are verified

all public contracts are compatible

all historical events required by the deployed system remain readable/replayable

required migration/upgrade paths have been verified

required recovery scenarios have been verified

performance SLOs pass under the agreed workload

the exact artifact/deployment manifest tested is the one being promoted
```

That is a much stronger definition.

### Production is the outermost test loop

Kent explicitly includes monitoring in his broader concept of test/feedback mechanisms. citeturn12view0

Production therefore completes—not replaces—the test strategy:

```text
requirements
  ↓
acceptance tests
  ↓
implementation tests
  ↓
pre-production verification
  ↓
deployment
  ↓
production telemetry
  ↓
new information
  ↓
new/revised requirement or test case
```

Monitor at least the externally meaningful dimensions Google identifies: latency, errors, traffic, and saturation, with service-specific indicators such as event lag and projection freshness added for your architecture. citeturn13view4

## Governance, maintainability, and the final Definition of Done

### A test should have one reason to exist

Every test should be classifiable as answering one of these questions:

```text
What requirement does this prove?

What domain rule does this protect?

What public contract does this protect?

What infrastructure semantic does this protect?

What architecture rule does this protect?

What failure invariant does this protect?

What performance requirement does this protect?
```

A test with no convincing answer is a candidate for deletion.

That keeps test volume from becoming the goal.

The goal is **confidence per unit of maintenance effort**.

This is consistent with Beck's view that not every conceivable test automatically deserves to exist; he frames automation economically in terms of whether the test increases useful validated feedback. citeturn12view3

### Keep three categories of coupling explicit

A maintainable test suite for this architecture should deliberately recognize three broad categories.

**Behavioral coupling is good.**

```text
"When an authorized filing satisfying rule X is submitted,
the registry records outcome Y."
```

The test should fail if that business behavior changes.

**Contract coupling is intentional.**

```text
"This event has these externally promised semantics."
```

The test should fail if you accidentally break a consumer.

**Implementation coupling is generally bad.**

```text
"The handler calls FooService, then BarRepository, exactly once each."
```

That usually means a harmless refactor breaks tests.

Kent's distinction between behavioral and structure-sensitive tests provides the conceptual basis for this separation. citeturn12view0turn12view1

### Keep tests in the ubiquitous language

Prefer:

```clojure
(testing "a dissolved entity cannot perform this filing"
  ...)
```

over:

```clojure
(testing "handler calls validation branch 4"
  ...)
```

Likewise, create domain-oriented fixture functions:

```clojure
(given-registered-company ...)
(given-officer-appointed ...)
(when-filing-submitted ...)
(then-filing-is-rejected ...)
```

rather than technical fixture APIs that expose tables and repositories everywhere.

The test vocabulary should read like the Use Cases and domain model.

That simultaneously improves:

```text
traceability
readability
diagnostic value
reviewability by domain experts
refactoring resilience
```

### Do not directly test private functions by default

Your private functions should normally be exercised through stable behavioral surfaces.

A test directly coupled to a private implementation function creates precisely the structural sensitivity you are trying to avoid.

When a private function becomes sufficiently important and conceptually stable that it desperately needs direct exhaustive testing, ask whether it has actually revealed a domain concept or algorithm that deserves an explicit abstraction.

### Treat flaky tests as defects

Your gating suite should behave according to Beck's deterministic and isolated desiderata. citeturn12view0

Do not normalize:

```text
"retry CI three times"
```

as a testing strategy.

Separate genuinely stochastic/fault-injection workloads from deterministic release gates, capture reproducible seeds/history wherever possible, and investigate flaky deterministic tests as defects in either:

```text
the test
the test infrastructure
or the production concurrency model
```

### Measure the test system itself

I would maintain a small engineering dashboard containing:

| Metric | What it tells you |
|---|---|
| Requirement verification coverage | Have all committed requirements been proven? |
| Unverified slices | What cannot currently ship? |
| Orphan test cases | Is traceability decaying? |
| Flaky test rate | Can results be trusted? |
| Focused test p95 duration | Is TDD feedback still fast? |
| Fast-suite p95 duration | Is local development slowing? |
| PR feedback time | How quickly developers learn a change is wrong |
| Refactor-induced test churn | Are behavioral tests becoming structure-sensitive? |
| Historical-event replay failures | Are upgrades threatening stored history? |
| Projection rebuild discrepancies | Are read models drifting? |
| Performance-SLO regressions | Is the system becoming slower? |
| Escaped defects by missing test category | Where is the strategy weak? |

I would deliberately **not** put "line coverage percentage" at the top of this dashboard.

The most important coverage number for this system is:

> **How much of the required system behavior has executable passing evidence?**

### Definition of Done for a Use-Case Slice

A slice reaches **Verified** only when all of the following that apply are true:

| Requirement | Required evidence |
|---|---|
| Use-Case flows identified | Slice references them |
| Test Cases defined | Stable IDs exist |
| Business behavior | Acceptance evidence passes |
| Domain rules | Programmer/property tests pass |
| Module interactions | Only public interfaces used |
| API contract | Contract verification passes |
| Integration-event contract | Producer/consumer verification passes |
| Event history | Relevant replay/version tests pass |
| Read-model behavior | Projection tests pass |
| Inbox/outbox semantics | Relevant reliability tests pass |
| Concurrency semantics | Relevant invariant tests pass |
| UI behavior | UI verification passes where required |
| Performance | Slice/system SLOs pass where applicable |
| System-wide requirements | Applicable evidence passes |
| Traceability | Requirement → Test Case → result is complete |
| Artifact identity | Evidence corresponds to releasable artifact |

This interpretation is directly compatible with Use-Case 3.0's lifecycle: a slice is not merely "implemented"; it progresses through implementation to verification before it is ready for release. citeturn5view3

### Definition of Done for a release

The release condition should be mechanically reducible to:

```text
Every required release slice is Verified
AND
every required system-wide requirement has passing evidence
AND
every applicable compatibility contract passes
AND
every required performance SLO passes
AND
the deployment manifest equals the verified manifest.
```

Or formally:

\[
ReleaseReady(R)
=
\bigwedge_{s \in RequiredSlices(R)} Verified(s)
\land
\bigwedge_{q \in SystemRequirements(R)} Verified(q)
\]

This gives the architecture, requirements process, testing approach, and release process a single coherent model.

The most important consequence is that your architecture stops producing separate islands called "requirements," "unit tests," "integration tests," "performance tests," and "release QA."

Instead the structure becomes:

```text
                         USE CASE
                            │
                            ▼
                      USE-CASE SLICE
                            │
                            ▼
                     USE-CASE TEST CASE
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
        DOMAIN TESTS   CONTRACT TESTS   SYSTEM TESTS
             │              │              │
             ├──────────────┼──────────────┤
             │              │              │
             ▼              ▼              ▼
       EVENT / CQRS     RELIABILITY     PERFORMANCE
        EVIDENCE         EVIDENCE        EVIDENCE
             │              │              │
             └──────────────┼──────────────┘
                            ▼
                         VERIFIED
                            │
                            ▼
                          RELEASE
                            │
                            ▼
                  PRODUCTION TELEMETRY
```

That design preserves what is strongest in both approaches: **Use-Case 3.0 gives you end-to-end, auditable proof that stakeholder requirements were delivered; Kent Beck's approach gives developers a fast, behavioral, refactoring-friendly feedback system for building those requirements correctly.** Use-Case 3.0 explicitly places tests at the center of deciding whether slices are complete, while Beck's test desiderata provide the appropriate design pressure for keeping the executable test estate fast, deterministic, readable, behavioral, and maintainable. citeturn5view1turn12view0turn12view2