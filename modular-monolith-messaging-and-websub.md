# Messaging and Integration Architecture for the Corporate Registry Modular Monolith

## 1. Purpose

This document defines how modules in the corporate registry communicate in a **modular monolith with event-driven architecture**.

The core model is deliberately small:

- **Commands** request that one module do something.
- **Queries** ask one module for information and return a result.
- **Integration events** announce that something has happened and may be consumed by zero, one, or many modules.
- **Domain events** record meaningful facts inside a bounded context or aggregate.
- **Policies** react to facts and decide that another action should be requested.
- **Process managers** coordinate long-running processes across multiple messages and modules.
- **Outbox/inbox** provide reliable asynchronous delivery across module boundaries.
- **WebSub** is an external publication mechanism for systems outside the registry, not the internal module messaging mechanism.

The most important architectural rule is:

> A module may only interact with another module through that module's public contract: commands, queries, and integration events.

The internal implementation of another module—its aggregates, tables, projections, policies, process managers, event streams, repositories, or functions—is private.

---

## 2. The mental model

At the highest level, module communication has three meanings.

| Intent | Mechanism | Cardinality | Typical execution |
|---|---|---:|---|
| **Do something** | Command | exactly one logical consumer | asynchronous across modules |
| **Tell others something happened** | Integration event | zero to many consumers | asynchronous |
| **Tell me something** | Query | one provider, one result | synchronous |

```text
Command
    "Please do this"
          |
          v
    one destination

Integration Event
    "This happened"
          |
     +----+----+----+
     |         |    |
     v         v    v
  consumer  consumer consumer

Query
    "Tell me this"
          |
          v
       provider
          |
          v
        result
```

The three forms are deliberately different because they have different semantics.

Do not collapse them into a generic `message` abstraction at the application boundary if doing so hides those semantics.

---

## 3. The module is the architectural boundary

A module represents a cohesive business capability or part of a bounded context.

For example:

```text
Company Register
Payments
Incorporation
Disclosure
Identity
Notifications
```

A module owns its:

```text
domain model
aggregates
domain events
policies
process managers
database schema / event streams
projections
inbox
outbox
commands it handles
queries it provides
integration events it publishes
```

Other modules do not reach inside it.

```text
+------------------------------------------------------+
| Company Register Module                              |
|                                                      |
| Public                                               |
|   Commands                                           |
|   Queries                                            |
|   Integration events                                 |
|                                                      |
| ---------------------------------------------------- |
| Private                                              |
|   Aggregates                                         |
|   Domain events                                      |
|   Policies                                           |
|   Process managers                                   |
|   Event streams                                      |
|   Projections                                        |
|   Repositories                                       |
|   Database tables                                    |
+------------------------------------------------------+
```

The public contract should be small, explicit, versionable, and understandable in business terms.

---

## 4. Commands

### 4.1 What a command means

A command means:

> A caller is requesting that one capability perform an action.

Examples:

```clojure
{:command/type :company-register/register-company
 :data
 {:application-id #uuid "..."
  :company-name "Example Limited"}}
```

```clojure
{:command/type :payments/request-payment
 :data
 {:application-id #uuid "..."
  :amount 5000
  :currency :eur}}
```

A command is **directed**. It should have exactly one logical destination.

```text
Incorporation
      |
      | RegisterCompany
      v
Company Register
```

A command is therefore not pub/sub.

### 4.2 Cross-module commands

Inside the same module, a command can be handled directly by the module's application layer.

Across module boundaries, commands should normally travel through the reliable messaging path:

```text
Module A
   |
   | outgoing command
   v
outbox
   |
   v
dispatcher
   |
   v
Module B inbox
   |
   v
Module B command handler
```

This allows modules to remain temporally decoupled.

### 4.3 Command outcomes

A typical command flow is:

```text
command
   |
   v
load aggregate
   |
   v
decide
   |
   +------ rejection / business error
   |
   v
domain events
   |
   v
append events
   |
   v
possibly update synchronous projections
   |
   v
possibly write outgoing integration events / commands
   |
   v
commit transaction
```

The command itself is not a fact.

```text
RegisterCompany        command
CompanyRegistered      event
```

The command says what someone wants. The event says what actually happened.

---

## 5. Integration events

### 5.1 What an integration event means

An integration event says:

> A fact has occurred in one module that is intentionally exposed for other modules or external integrations to consume.

Example:

```clojure
{:event/type :company-register/company-registered
 :data
 {:company-number "12345678"
  :registered-at #inst "2026-08-22T08:00:00Z"}}
```

Unlike a command, the producer does not need to know who cares.

```text
CompanyRegistered
        |
   +----+-------------+----------------+
   |                  |                |
   v                  v                v
Disclosure       Notifications      Analytics
```

That is genuine publish/subscribe.

The event may legitimately have zero, one, or many subscribers.

### 5.2 Integration events are contracts

Once another module depends on an integration event, it is part of the publishing module's public contract.

Its name, meaning, schema, version, ordering assumptions, delivery semantics, and security classification should be treated deliberately.

Prefer business facts such as:

```text
CompanyRegistered
FilingAccepted
PaymentConfirmed
ApplicationApproved
OfficerAppointmentRegistered
```

rather than infrastructure facts such as:

```text
RowInserted
ProjectionUpdated
AggregateVersionChanged
DatabaseWriteCompleted
```

---

## 6. Domain events and integration events are different

A **domain event** belongs to the internal domain model.

An **integration event** belongs to the module boundary.

```text
Aggregate
   |
   | emits
   v
Domain Event
   |
   | interpreted / mapped by module
   v
Integration Event
   |
   v
Outbox
```

They may look similar, but they should not automatically be the same artifact.

Reasons include:

- internal events can contain more detail than should cross the boundary;
- the domain model can evolve without forcing every consumer to change;
- the public event may need a different schema;
- one domain event may produce no integration event;
- several domain events may produce one integration event;
- an integration event may only be valid after a larger transaction or process has completed.

A useful rule is:

> Aggregates emit domain events. The module decides which business facts become integration events.

---

## 7. Queries

A query means:

> Give me information without changing authoritative business state.

Examples:

```text
GetCompany
GetApplication
GetDirectors
GetDocuments
GetPreviousFilings
SearchCompanies
```

Queries normally use projections/read models rather than replaying aggregates.

```text
caller
   |
   | query
   v
Module public query API
   |
   v
projection / read model
   |
   v
result
```

Queries are generally synchronous because the caller needs the answer.

The standard pattern therefore becomes:

```text
                 MODULE PUBLIC INTERFACE

              +-------------------------+
              |         Module          |
              +-------------------------+
                 ^          ^          |
                 |          |          |
              command      query    integration event
                 |          |          |
               async       sync      async
                 |          |          |
             1 consumer   1 result  0..N subscribers
```

Queries should not normally be placed on the asynchronous message bus.

---

## 8. Policies

A policy is useful when the business says:

> When this fact is true, another action should be requested.

Example:

```text
PaymentConfirmed
      |
      v
Policy:
"paid applications may enter examination"
      |
      v
StartExamination
```

A policy is typically reactive and comparatively small.

It does not usually need long-lived workflow state.

A useful rule is:

> Use a policy when the reaction can be decided from the triggering fact plus ordinary domain information, without remembering a multi-step conversation.

---

## 9. Process managers

A process manager coordinates a business process that crosses multiple steps, messages, or modules and therefore needs durable process state.

```text
ApplicationSubmitted
        |
        v
 Incorporation PM
        |
        v
RequestPayment
        |
        v
PaymentConfirmed
        |
        v
 Incorporation PM
        |
        v
StartExamination
        |
        v
ExaminationApproved
        |
        v
 Incorporation PM
        |
        v
RegisterCompany
        |
        v
CompanyRegistered
        |
        v
 Incorporation PM
        |
        v
process complete
```

A process manager is simultaneously a consumer, producer, and state machine.

Its state might include:

```clojure
{:process/id #uuid "..."
 :application-id #uuid "..."
 :payment-status :confirmed
 :examination-status :approved
 :registration-status :requested}
```

The process manager should remain about **business coordination**.

Outbox retries, HTTP delivery, database locks, and queue mechanics are infrastructure concerns and should not leak into process state.

---

## 10. Aggregates, policies, and process managers as message participants

```text
                        incoming message
                               |
                               v
                     +-------------------+
                     | module application |
                     +-------------------+
                               |
               +---------------+---------------+
               |               |               |
               v               v               v
           aggregate         policy      process manager
               |               |               |
               +---------------+---------------+
                               |
                         resulting facts
                         / intentions
                               |
                               v
                           outgoing
                           messages
```

Keep one important distinction:

- aggregates primarily emit **domain events**;
- policies may derive **commands** from facts;
- process managers may derive **commands** from process state and facts;
- the module application/integration layer decides what is placed in the **outbox** as a cross-module command or integration event.

This avoids making domain objects aware of infrastructure.

---

## 11. The internal dispatcher

The modular monolith needs a small internal message-dispatch mechanism.

Conceptually there are two operations:

```clojure
(command/send command)
```

and:

```clojure
(event/publish integration-event)
```

Their semantics are different.

```text
send-command
      |
      v
one destination

publish-event
      |
  +---+---+---+
  |       |   |
  v       v   v
0..N subscribers
```

A routing registry might conceptually contain:

```clojure
{:commands
 {:company-register/register-company
  :company-register}

 :events
 {:company-register/company-registered
  #{:disclosure
    :notifications
    :analytics}}}
```

The actual implementation can be data-driven, multimethod-based, protocol-based, or generated from module descriptors.

What matters is that the semantics remain explicit.

---

## 12. Do not use generic pub/sub semantics for commands

A generic API such as:

```clojure
(publish {:type :register-company ...})
```

hides whether the message requires one handler or many subscribers.

Prefer APIs that reveal intent:

```clojure
(send-command ...)
```

```clojure
(publish-event ...)
```

This turns an architectural rule into something visible in code.

---

## 13. Outbox and inbox

The outbox/inbox pair provides reliable asynchronous messaging while keeping the system a modular monolith.

### 13.1 Outbox

When a module performs a business transaction, the outgoing message is recorded in the same local database transaction as the state change that caused it.

```text
Module A transaction
        |
        +-- append domain event
        |
        +-- update local state / projection if required
        |
        +-- record outgoing message in outbox
        |
        v
      COMMIT
```

The key invariant is:

> Either the business state change and its outgoing message are both committed, or neither is committed.

This avoids:

```text
business state committed
process crashes
message never sent
```

### 13.2 Dispatcher

For a command:

```text
Module A outbox
      |
      v
dispatcher
      |
      v
Module B inbox
```

For an integration event:

```text
Module A outbox
       |
       v
dispatcher
   +---+--------+--------+
   |            |        |
   v            v        v
B inbox       C inbox   D inbox
```

For pub/sub delivery, each target module should get its own durable delivery record.

That prevents one failed consumer from blocking or corrupting delivery state for another.

### 13.3 Inbox

```text
incoming message
       |
       v
inbox
       |
       +-- already processed? --> return previous/no-op
       |
       v
execute handler
       |
       +-- append local domain events
       |
       +-- update local state
       |
       +-- write new outgoing messages
       |
       +-- mark inbox message processed
       |
       v
     COMMIT
```

The inbox gives the destination module idempotency.

---

## 14. Delivery semantics

The realistic internal guarantee is:

> At least once delivery, with idempotent processing.

Do not design around exactly-once transport.

Exactly-once business effect is achieved through application-level idempotency.

Each message should therefore have a stable identifier:

```clojure
{:message/id #uuid "..."
 :message/type :company-register/company-registered
 :message/correlation-id #uuid "..."
 :message/causation-id #uuid "..."
 :data {...}}
```

The inbox can enforce a uniqueness rule on `message-id`.

If a message is delivered twice:

```text
first delivery  -> process
second delivery -> detect duplicate -> no second business effect
```

---

## 15. Correlation and causation

Cross-module workflows are easier to understand when messages carry correlation and causation identifiers.

```text
ApplicationSubmitted        message A
       |
       v
RequestPayment              message B
       |
       v
PaymentConfirmed            message C
       |
       v
StartExamination            message D
```

Then:

```text
B.causation-id = A.message-id
C.causation-id = B.message-id
D.causation-id = C.message-id
```

while all messages may share:

```text
correlation-id = incorporation-process-id
```

This lets you reconstruct:

```text
what happened?
why did it happen?
what caused this command?
which business process did it belong to?
```

without coupling modules.

---

## 16. Ordering

Do not assume global message ordering.

The important question is:

> What ordering does a particular business rule actually require?

Possible ordering scopes include:

```text
per aggregate
per stream
per process manager
per company
per application
```

If ordering matters, encode the ordering scope explicitly.

Do not impose global ordering on the entire system unless there is a genuine business requirement.

---

## 17. Failure and retries

Infrastructure may retry message delivery.

Business logic should not contain retry machinery.

Keep the concerns separate:

```text
business:
    what should happen?

infrastructure:
    was delivery successful?
    should it be retried?
    when?
    how many times?
    what becomes dead-lettered?
```

The module should see the same logical message regardless of how many transport attempts occurred.

---

## 18. The public contract of a module

A useful architectural artifact for every module is an explicit contract.

Example:

```markdown
## Company Register

### Consumes commands

- RegisterCompany
- CorrectCompany
- DissolveCompany

### Consumes integration events

- IncorporationApproved
- CourtOrderReceived

### Provides queries

- GetCompany
- SearchCompanies
- GetCompanyHistory

### Publishes integration events

- CompanyRegistered
- CompanyCorrected
- CompanyDissolved
```

This becomes one of the clearest ways to understand the architecture without opening implementation code.

---

## 19. Example: incorporation across modules

```text
Incorporation module
        |
        | RequestPayment
        v
Payments module
        |
        | PaymentConfirmed
        v
Incorporation process manager
        |
        | StartExamination
        v
Examination capability
        |
        | ExaminationApproved
        v
Incorporation process manager
        |
        | RegisterCompany
        v
Company Register module
        |
        | CompanyRegistered
        +-------------------+
        |                   |
        v                   v
Disclosure            Notifications
```

The semantic sequence is:

```text
RequestPayment       command
PaymentConfirmed     integration event
StartExamination     command
ExaminationApproved  integration event
RegisterCompany      command
CompanyRegistered    integration event
```

The model naturally alternates between:

```text
request
fact
request
fact
request
fact
```

That is a useful way to reason about an event-driven business process.

---

## 20. Synchronous calls between modules

A modular monolith does not forbid synchronous calls.

They are appropriate when the semantics require an immediate answer.

Queries are the clearest example:

```text
Module A
   |
   | GetCompany
   v
Company Register query API
   |
   v
projection
   |
   v
company DTO
```

Avoid synchronous calls that cause one module to execute another module's business command inside the caller's transaction.

That creates temporal and transactional coupling.

Prefer outbox/inbox delivery for cross-module state changes.

---

## 21. Transaction boundaries

Each module owns its transaction.

Do not create a distributed transaction across module business operations.

```text
Incorporation transaction
    commits RequestRegistration command

later

Company Register transaction
    processes RegisterCompany
    commits CompanyRegistered
```

This gives:

```text
local atomicity
+
cross-module eventual consistency
```

The process manager exists precisely because the overall business process spans multiple local transactions.

---

## 22. Eventual consistency

Eventual consistency is not an accidental defect.

It is part of the architecture whenever business work crosses transaction boundaries.

A process might temporarily be:

```text
application approved
registration requested
company not yet visible
```

The system therefore needs explicit answers to:

```text
which intermediate states are valid?
what may the user see?
what operations are allowed?
how is completion observed?
what happens after failure?
```

These are requirements questions as much as technical questions.

---

## 23. Where WebSub fits

WebSub belongs at the **external integration boundary**.

Its purpose is:

> Let systems outside the registry subscribe to changes in public registry resources without polling the REST API.

```text
Corporate Registry
      |
      | public company changed
      v
WebSub publication adapter
      |
      v
WebSub hub
   +--+----------+-----------+
   |             |           |
   v             v           v
Bank         Regulator   Data provider
```

WebSub is therefore not the mechanism used between internal modules.

---

## 24. Why WebSub should not be the internal module bus

Using WebSub internally would introduce:

```text
HTTP topic URLs
hub discovery
callback URLs
subscription verification
leases
HTTP push delivery
HMAC WebSub signatures
```

between components that already live in one deployable application.

That would turn a module boundary into unnecessary distributed-systems machinery.

The internal modules already have a better mechanism:

```text
commands
integration events
queries
outbox
inbox
dispatcher
```

WebSub solves a different problem.

---

## 25. Internal integration versus external publication

```text
                         CORPORATE REGISTRY

                     +----------------------+
                     | Company Register     |
                     +----------------------+
                               |
                               | CompanyRegistered
                               v
                            Outbox
                               |
                          dispatcher
                               |
             +-----------------+------------------+
             |                 |                  |
             v                 v                  v
      Disclosure inbox   Notification inbox   WebSub adapter
             |                 |                  |
             v                 v                  v
        module logic       module logic        WebSub hub
                                                 |
                                     +-----------+-----------+
                                     |           |           |
                                     v           v           v
                                    Bank     Regulator   Data service
```

The WebSub adapter consumes selected internal integration events and translates them into changes to public WebSub topics.

The domain does not know WebSub exists.

---

## 26. WebSub topics are not domain events

WebSub is topic-oriented.

For example:

```text
https://registry.example/companies/12345678/changes
```

is a topic.

The internal fact may be:

```text
CompanyNameChanged
```

The relationship can be:

```text
CompanyNameChanged
       |
       v
public company-change projection updated
       |
       v
/companies/12345678/changes changed
       |
       v
WebSub hub notified
       |
       v
external subscribers receive update
```

The WebSub topic is a public web resource.

The internal integration event is a business fact.

Do not collapse the two concepts.

---

## 27. Angular does not need WebSub

Angular is a browser application.

For live browser updates, use HTTP plus **Server-Sent Events (SSE)**, or WebSockets when true bidirectional communication is required.

```text
Angular
   |
   +-- HTTP commands
   |
   +-- HTTP queries
   |
   +-- SSE
          ^
          |
      Clojure backend
```

| Need | Mechanism |
|---|---|
| Browser changes state | HTTP command endpoint |
| Browser reads state | HTTP query endpoint |
| Browser receives live updates | SSE / WebSocket |
| External machine subscribes to public registry changes | WebSub |

---

## 28. Clojure implementation shape

The Clojure implementation should preserve these concepts in namespaces and APIs rather than hiding them.

```text
src/
  registry/
    messaging/
      command.clj
      integration_event.clj
      dispatcher.clj
      outbox.clj
      inbox.clj

    incorporation/
      api.clj
      commands/
      queries/
      domain/
      policies/
      process_managers/
      projections/

    company_register/
      api.clj
      commands/
      queries/
      domain/
      policies/
      process_managers/
      projections/

    websub/
      publisher.clj
      topics.clj
      adapter.clj
```

The exact folder structure is less important than dependency direction.

Business modules should depend on messaging concepts/ports, not on HTTP, WebSub, database polling, or transport implementation.

---

## 29. Suggested message envelopes

A command envelope might look like:

```clojure
{:message/id             #uuid "..."
 :message/kind           :command
 :command/type           :company-register/register-company
 :message/correlation-id #uuid "..."
 :message/causation-id   #uuid "..."
 :message/created-at     #inst "..."
 :data
 {:application-id #uuid "..."}}
```

An integration-event envelope might look like:

```clojure
{:message/id             #uuid "..."
 :message/kind           :integration-event
 :event/type             :company-register/company-registered
 :message/correlation-id #uuid "..."
 :message/causation-id   #uuid "..."
 :message/occurred-at    #inst "..."
 :data
 {:company-number "12345678"}}
```

Keep transport metadata separate from business data.

Do not put implementation-specific retry counters or database row IDs inside the business payload.

---

## 30. Public interfaces as ports

A module's public interface can be thought of as a set of ports.

```text
Company Register

driving ports:
    RegisterCompany
    CorrectCompany
    DissolveCompany
    CourtOrderReceived

query ports:
    GetCompany
    SearchCompanies

published facts:
    CompanyRegistered
    CompanyCorrected
    CompanyDissolved
```

Adapters may invoke or implement those ports:

```text
HTTP
internal command dispatcher
scheduled job
integration-event consumer
```

The domain remains independent of how the request arrived.

---

## 31. Testing strategy

### 31.1 Pure domain tests

Test aggregates, policies, and process managers without infrastructure.

Example:

```text
given:
    PaymentConfirmed

when:
    incorporation policy reacts

then:
    StartExamination command is requested
```

### 31.2 Module application tests

Test:

```text
incoming message
    ->
handler
    ->
domain decision
    ->
outgoing message
```

without needing the real dispatcher.

### 31.3 Outbox/inbox integration tests

Prove:

```text
business state + outbox are atomic
duplicate inbox message does not duplicate business effect
failed delivery can retry
event fan-out creates independent deliveries
```

### 31.4 Architecture tests

Automatically check rules such as:

```text
modules may not import another module's private namespaces
cross-module commands have one owner
integration events may have multiple subscribers
queries go through declared public interfaces
```

### 31.5 End-to-end process tests

Test the business outcome, for example:

```text
submit
 ->
pay
 ->
examine
 ->
approve
 ->
register
 ->
publicly visible company
```

The test should prove the outcome, not merely that messages moved.

---

## 32. Observability

Message-based systems become difficult to operate if causal relationships are invisible.

Log and trace at least:

```text
message id
message type
correlation id
causation id
origin module
destination module for commands
consumer module for events
attempt number
received time
processed time
result
```

This lets operations answer:

```text
Why has this application not completed?
Which command is waiting?
Which event caused this action?
Which module failed?
Was the message retried?
Did the business effect already happen?
```

The operational model should reinforce the semantic model.

---

## 33. Security

Module boundaries are also security boundaries.

A receiving module must not trust a message merely because it is internal.

It should still enforce its own invariants and authorization/business rules as appropriate.

A `RegisterCompany` command does not bypass the Company Register's rules merely because it originated from the Incorporation module.

The destination owns the decision.

For WebSub, only explicitly public/disclosable information should be exposed in public topics.

Restricted information should use separately designed authenticated integration mechanisms.

---

## 34. A useful rule for ownership

For every cross-module interaction, ask:

### Command

> Which one module owns the capability being requested?

### Query

> Which one module owns the information being requested?

### Integration event

> Which module owns the fact and publishes its meaning?

### Consumer

> Why does this module care about the fact?

This prevents boundaries from becoming arbitrary.

---

## 35. What not to do

Avoid these patterns:

```text
Module A reads Module B database tables directly.
Module A loads Module B aggregate directly.
Module A updates Module B projection directly.
Module A calls Module B private function.
Module A publishes a command to many subscribers.
Module A knows every consumer of its integration event.
Modules exchange raw domain events by default.
Modules use WebSub internally.
Queries are sent through the asynchronous message dispatcher by default.
Business logic depends on outbox retry mechanics.
Process managers contain HTTP/queue infrastructure details.
```

Every one of these weakens the meaning of the module boundary.

---

## 36. The simplest complete model

```text
                 WHAT DOES THE CALLER MEAN?

                       /    |    \
                      /     |     \
                     v      v      v

                DO THIS   TELL ME   THIS HAPPENED
                   |         |           |
                   v         v           v
               COMMAND     QUERY   INTEGRATION EVENT
                   |         |           |
                   v         v           v
                1 owner    1 result   0..N consumers
                   |         |           |
                 async      sync        async
                   \         |          /
                    \        |         /
                     +-------+--------+
                             |
                             v
                      MODULE CONTRACT
```

Reliable cross-module path:

```text
producer
   |
   v
outbox
   |
   v
dispatcher
   |
   v
inbox
   |
   v
consumer
```

External machine-push path:

```text
integration event
       |
       v
WebSub publication adapter
       |
       v
WebSub hub
       |
       v
external subscribers
```

---

## 37. Final architecture rules

1. **Commands request action.** They have one logical destination.
2. **Integration events announce facts.** They may have zero to many consumers.
3. **Queries request information.** They are normally synchronous.
4. **Domain events are internal domain facts.** They are not automatically integration events.
5. **Aggregates enforce invariants and emit domain events.**
6. **Policies react to facts and request actions when no durable workflow state is needed.**
7. **Process managers coordinate multi-step, cross-module processes using durable state.**
8. **Modules communicate across boundaries only through declared public contracts.**
9. **Cross-module state changes are asynchronous by default.**
10. **Outbox + inbox provide reliable at-least-once delivery.**
11. **Consumers must be idempotent.**
12. **Do not depend on global ordering. Define only the ordering a business rule actually needs.**
13. **Each module owns its own transaction.**
14. **Cross-module workflows are eventually consistent by design.**
15. **The destination module always owns the final business decision.**
16. **WebSub is for external subscribers, not internal module messaging.**
17. **Angular should normally use HTTP plus SSE/WebSockets, not WebSub.**
18. **The domain must not know about WebSub, HTTP, dispatcher mechanics, inboxes, or retries.**
19. **The module contract should remain understandable in business language.**
20. **Prefer explicit semantics over generic messaging machinery.**

---

## 38. One-sentence summary

> Inside the modular monolith, modules request work with commands, answer questions with queries, announce facts with integration events, coordinate workflows with policies/process managers, and rely on outbox/inbox for durable asynchronous delivery; WebSub begins only at the registry's external boundary, where selected public facts are exposed to external subscribers.
