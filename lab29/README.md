# Lab 29: internal messages, external publication

Lab 28 crossed a network and found that retries, deadlines and idempotency are
properties of an integration, not decorations around a function call. This lab
asks the question immediately underneath that machinery: **what does each
message mean, who owns it, and how many consumers may receive it?**

The answer separates two boundaries that are easy to blur:

- inside the modular monolith, commands request work and integration events
  announce facts through explicit module contracts;
- outside it, WebSub publishes selected facts as changes to public resources.

**The one idea: internal module messaging is organised by business intent;
WebSub begins only at the external publication boundary.**

```bash
bb test     # 129 tests against real PostgreSQL and the provider adapters
```

## The conversation

```text
Catalog
  |
  | price-changed event ───────────────┬──────────────▶ Ordering
  |                                    |
  |                                    └──────────────▶ WebSub adapter
  |                                                        |
  |                                                 public product topic
  |                                                        |
  |                                                 external subscribers
  |
Ordering
  |
  | charge-order command ───────────────────────────▶ Payments
  |                                                       |
  |◀──────────────────── payment-succeeded event ─────────┘
  |
  | send-receipt command ───────────────────────────▶ Notifications
```

There are four business modules in one deployment, plus one publication
adapter. They do not share private functions or tables. PostgreSQL roles enforce
the same boundary the namespaces describe.

The arrows look similar, but their meanings and cardinalities are not.

| Intent | Shape | Destination |
|---|---|---|
| ask one capability to act | command | exactly one module |
| expose something that happened | integration event | zero to many modules |
| ask one capability for data | query | one public module API |
| expose a public resource change | WebSub notification | verified external subscribers |

## Commands are sent; events are published

A command names the capability being requested:

```clojure
{:message/id   #uuid "..."
 :message/kind :command
 :command/type :payments/charge-order
 :metadata     {:causation-id #uuid "..."
                :correlation-id #uuid "..."}
 :data         {:order-id #uuid "..."
                :order-fact-id #uuid "..."
                :total-cents 600
                :customer-email "ada@example.test"
                :payment-method "pm_card_visa"}}
```

Payments owns `:payments/charge-order`, so exactly one module may declare that
it handles it. No destination is a routing error and two destinations are an
incoherent architecture, not fan-out.

An integration event exposes a fact without naming who cares:

```clojure
{:message/id   #uuid "..."
 :message/kind :integration-event
 :event/type   :catalog/price-changed
 :metadata     {:causation-id #uuid "..."
                :correlation-id #uuid "..."}
 :payload      {:fact-id #uuid "..."
                :product-id #uuid "..."
                :product-name "vanilla"
                :price-cents 300}}
```

Ordering consumes that fact to maintain its own price book. The WebSub adapter
consumes the same fact to maintain a public product resource. Catalog knows
neither consumer, and an event with no consumers is still legitimate.

The different `:data` and `:payload` keys preserve the vocabulary established
in Labs 1–3: a command carries the request data for its one destination; an
integration event is a public fact in transit. There is deliberately no generic
constructor that lets a caller avoid choosing which one it means.

## The contracts are executable data

Each module declares the public surface it owns:

```clojure
{:module           :payments
 :handles-commands #{:payments/charge-order}
 :consumes-events  #{}
 :publishes-events #{:payments/payment-succeeded}
 :provides-queries #{:payments/get-payment}}
```

`system.clj` derives the routing table from those values. Startup rejects:

- a command handled by more than one module;
- an event type published by more than one module;
- a subscription to an event nobody publishes;
- one type used as both a command and an event.

That makes the module contracts the source of truth rather than documentation
beside a hand-written routing table. Architecture tests also prevent one module
from requiring another module's private slices or reading its database tables.

The module boundary is therefore visible in four places:

```text
public API          the supported calls
contract value      commands, queries and facts
namespace rules     no private cross-module requires
PostgreSQL role     no private cross-module SQL
```

## A process manager owns the sequence

Placing an order does two different things:

1. Ordering publishes `:ordering/order-placed`, a fact for whoever may care.
2. Ordering sends `:payments/charge-order`, a request to the one capability
   that owns taking money.

When Payments later publishes `:payments/payment-succeeded`, Ordering's
fulfilment process manager receives it. The manager remembers that this order
was awaiting payment, moves it to `paid`, and sends
`:notifications/send-receipt`.

```text
awaiting-payment
       |
       | payment-succeeded
       v
      paid ────────── send-receipt ──────────▶ Notifications
```

This is not a stateless policy. Whether a payment may advance the conversation
cannot be decided from the incoming fact alone; Ordering must remember the
process state. Delivery attempts and retry counts are absent from that state
because they belong to transport, not to the business conversation.

Notifications handles the command. It does not also subscribe directly to the
payment event: doing both would give two components ownership of the same next
step and could send two receipts.

## One message, one delivery per consumer

Labs 25–28 tracked publication on the outbox message. That is sufficient while
one message has one consumer. It is wrong for real fan-out.

Suppose Ordering accepts a price change while the WebSub adapter fails:

```text
message M
  ├── Ordering    delivered
  └── WebSub      failed, attempt 1
```

With one shared flag, either Ordering receives M again or WebSub's failure is
forgotten. Lab 29 makes the failure boundary explicit:

```sql
PRIMARY KEY (message_id, consumer)
```

Each module owns an outbox and a delivery table. On the first relay pass, the
routing contract expands a queued message into one durable row per current
consumer. Each row then advances independently through `delivered`, `failed`
and `dead` states.

After three failed relay passes, only that consumer's delivery is moved to a
dead-letter row:

```text
catalog/price-changed
  Ordering    delivered
  WebSub      dead after 3 attempts
```

Healthy consumers are not retried, a poison consumer does not block messages
behind it, and an operator can revive the failed `(message, consumer)` pair.
The outbox message is settled once every consumer is either delivered or dead.

The envelope is stored as EDN. JSON would erase the namespace from qualified
keyword routing keys such as `:catalog/price-changed`, leaving a body that
looks plausible but cannot be routed correctly after revival.

## Strict insertion and deliberate convergence

An arbitrary duplicate message id is an error. Catalog's command ledger,
product update and outbox insert share one transaction, so accidentally reusing
an outbox id must fail and roll back the whole business outcome.

There is one narrower operation in Payments. A synchronous authorization and a
later provider callback may independently discover the same successful payment.
Both derive the announcement id from the payment id and use
`enqueue-once!`, so those two paths converge on one public fact:

```text
generated message id collision       reject and roll back
derived payment announcement id      same outcome, keep one
```

The distinction is intentional. A generic `ON CONFLICT DO NOTHING` would hide
real identity collisions everywhere merely to solve one known coordination
case.

## WebSub starts outside the modules

WebSub does not replace the internal dispatcher. It solves a different
problem: an external machine wants to subscribe to changes in a public web
resource.

The adapter consumes selected Catalog integration events and folds them into
`websub.public_product`. It copies only fields deliberately approved for
publication:

```text
public_product
  product_id
  product_name
  description
  price_cents
  version

not copied
  supplier_cost_cents
```

That table is a projection, not Catalog's table exposed through another URL.
The separation prevents a future Catalog migration from silently publishing a
new private column.

A topic is the current public representation of one product:

```text
GET /topics/products/{product-id}
Link: <topic>; rel="self", <hub>; rel="hub"
```

The subscriber does not receive Catalog's event log. It receives a notification
that the topic changed and can fetch the current representation. A missed push
therefore does not create a permanent hole: fetching the topic converges on the
latest state.

## The hub does not trust a callback URL

Anyone can submit a subscription request. Without verification, an attacker
could nominate somebody else's callback and turn the hub into an amplification
service.

The lifecycle is therefore:

```text
subscription request
       |
       v
GET callback with a random challenge
       |
       +── wrong response ──▶ reject
       |
       v
challenge echoed
       |
       v
store verified, expiring subscription
```

The implementation verifies inline so the result is deterministic for this
lab; the WebSub flow normally permits accepting first and verifying
asynchronously. A lease is capped at one day, unsubscribe is verified too, and
expired subscriptions receive nothing.

Each subscriber may supply its own secret. The hub signs the exact body with
HMAC-SHA256 and sends the signature with the push. A briefly unreachable
subscriber gets the bounded retry and circuit-breaker behavior earned in Lab
28. A persistently failing subscription is dropped after three failed
distributions rather than retained forever.

There is deliberately no durable per-subscriber event log. WebSub publishes a
resource, not a history; recovery is a new GET of the topic.

## What remains from the earlier labs

Lab 29 changes the messaging model without discarding the guarantees already
earned:

- vertical slices still own their use cases and SQL;
- module roles still prevent cross-schema access;
- validation still wraps the public use-case boundary;
- trace context is still frozen in the outbox transaction and propagated in
  transport headers;
- business correlation remains persisted while trace context expires;
- provider vocabulary still stops in pure anticorruption layers;
- Stripe retries remain safe only because the payment id is its idempotency
  key;
- SendGrid still cannot promise one email across an ambiguous failure;
- search remains a module-owned projection and customer email remains absent
  from its indexes and telemetry.

The new platform namespaces share transport mechanics, not business logic.
Modules still choose their contracts, transactions and outcomes.

## Testing the boundaries

The suite is divided by the claim being proved:

| Test | Proves |
|---|---|
| dispatcher tests | a command has one destination, an event may have none, and four ways a contract set can fail to add up |
| fan-out tests | one broken consumer delays neither the other consumers of that message nor the messages behind it |
| process tests | a process manager refuses a step that already happened; a policy does not care what order facts arrive in |
| websub tests | verification of intent, leases, per-subscriber signatures, and what a stranger is never shown |
| vertical-slice tests | public module behavior and atomic outcomes against PostgreSQL |
| contract tests | both implementations of each provider port honor the same promises |
| integration tests | commands and events traverse four modules without shared transactions |
| idempotency tests | redelivery, races and crashes do not invent stronger guarantees |
| delivery tests | fan-out failures and dead letters are isolated per consumer |
| architecture tests | code, framework, provider and database boundaries remain intact |
| pure tests | calculations and translations remain values in, values out |

The first four are new in this lab, and the first two exist because the
designs they check are the ones lab 28 got wrong: a single `publish` that hid
cardinality, and a delivery record shared by every consumer of a message.

The tests interact through public APIs and contracts. Pure business rules and
translation functions are also tested directly because a pure core is useful
precisely because it is cheap to exercise without infrastructure.

## Limits

This is still one deployment and one PostgreSQL instance. Separate schemas and
roles enforce ownership, not independent availability. The dispatcher is an
in-process composition mechanism around durable outboxes, not a broker.

No global message ordering is promised. Ordering exists only where a business
rule names a scope: an order's fulfilment state, one module's outbox order, or
one topic's current version.

The WebSub implementation verifies subscriptions synchronously and pushes from
the publication adapter. A production hub would normally schedule both pieces
of work independently, apply SSRF protections to callback URLs, rotate secrets,
and expose operational controls for subscriptions. Those are deployment and
security concerns the lab names rather than pretending to solve.

## What's next

[Lab 30](../lab30) changes domain to a corporate Registry and applies Lab 27's
search-as-projection rule to French, German and Italian legal names. One filed
name remains authoritative while exact, prefix, phrase and fuzzy lookup each
receive the deliberately lossy key their access pattern needs.

## Running it

```bash
bb check    # lint and formatting
bb test     # module, messaging, delivery and integration behavior
```
