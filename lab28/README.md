# Lab 28: the network is not a function call

Inside one process, a call returns a value or throws. Across a network, the
caller may receive a value, receive a refusal, fail before the other side acts,
or fail **after** the other side acts but before its answer gets home. The last
two cases look identical from here.

That uncertainty is the subject of this lab.

**The one idea: crossing a process boundary changes what can be known and what
can safely be repeated. Reliability mechanisms bound that uncertainty; they do
not make a remote call local.**

The lab uses a payment provider and an email provider because they expose the
important difference. Stripe accepts an idempotency key, so an ambiguous call
can be repeated without charging twice. SendGrid does not offer the equivalent,
so an ambiguous send cannot safely be repeated. The same retry policy would be
wrong for one of them.

```bash
bb demo     # real Postgres and two fake providers on real sockets
```

```text
  charged, then the process crashes

    attempt 1   provider acted; local record was not updated
    attempt 2   same payment id sent as the same idempotency key

    HTTP requests       2
    payment intents     1
```

## The eight fallacies

The fallacies of distributed computing are assumptions that are convenient
inside a process and dangerous across a network. Lab 28 does not pretend to
solve all eight. It demonstrates the ones its design actually addresses and
names the rest as limits.

| fallacy | consequence in this lab | response |
|---|---|---|
| the network is reliable | a request or its answer can disappear | durable work, bounded retry, and provider idempotency where available |
| latency is zero | several individually reasonable waits become one unreasonable call | one deadline budget across all attempts |
| bandwidth is infinite | large messages and back-pressure change the design | not modelled here |
| the network is secure | anyone can post to a public webhook or replay an old request | verify the raw signed body, require freshness, then parse |
| topology does not change | an endpoint can fail, recover, or remain unavailable | a circuit breaker that opens, probes, and closes again |
| there is one administrator | the provider controls statuses, rate limits, delivery rules, and capabilities | anticorruption layers and provider-specific policies |
| transport cost is zero | serialization, connections, and remote calls consume time and money | visible in the extra calls, but not measured in this lab |
| the network is homogeneous | two providers do not offer the same guarantees | separate ports, adapters, translations, and retry sets |

The table is not a shopping list of middleware. A mechanism is useful only
when it preserves the meaning of the use case. Retrying an unsafe operation
more elegantly is still wrong.

## One uncertainty, handled at four timescales

```text
before the call       choose a stable identity; translate into provider terms
during the call       deadline; retry with backoff and jitter; circuit breaker
between relay passes  durable outbox; attempt count; dead-letter and revive
after an unknown      idempotency key or callback; reconciliation is still owed
```

### Before the call: write down an identity

Taking payment is deliberately three steps:

```text
1. CLAIM       one local transaction   inbox + payment in `requested`
2. AUTHORIZE   no local transaction    remote call using the payment id as key
3. RECORD      one local transaction   outcome + outgoing fact
```

Lab 20 showed what an inbox and outbox can make atomic in one database. The
gap around step 2 is what they cannot make atomic: no Postgres transaction can
include somebody else's payment system.

The payment id is therefore generated and committed before the first remote
attempt. If the provider acts and the process crashes before step 3, the retry
reuses that id as `Idempotency-Key`. The provider, which knows whether it
acted, returns the first result instead of taking the money again.

A race needs the same identity. Two workers can propose different payment ids
for one order, so the claim uses:

```sql
ON CONFLICT (order_id) DO UPDATE SET order_id = EXCLUDED.order_id
RETURNING payment_id
```

Both workers leave with the id that won. `DO NOTHING` followed by using each
worker's candidate would produce two valid keys and two valid charges.

### During the call: bound help

`platform/resilience.clj` is the only namespace that knows the resilience
library. The adapters describe provider semantics; the shared policy supplies
the mechanics:

```clojure
{:max-retries     3
 :backoff-ms      [50 500 2.0]
 :jitter-factor   0.3
 :max-duration-ms 5000}
```

Backoff avoids hammering a provider during a brief failure. Jitter prevents a
fleet of callers from returning in lockstep. The maximum duration bounds the
**whole operation**, not each attempt: one ten-second timeout repeated four
times is a forty-second call even if every individual timeout looks sensible.

Retries must also stop when the failure is no longer brief. Each provider owns
a circuit breaker shared across its calls. Enough failures open it, an open
breaker rejects work without touching the provider, and a later half-open
probe allows a recovered provider back in. An open breaker is translated into
`:provider-circuit-open`; library exception types do not escape the boundary.

### What may be retried is a business statement

The adapters declare different retry sets:

```clojure
;; Stripe: the same idempotency key makes an unknown outcome repeatable
#{:provider-unreachable :provider-unavailable}

;; SendGrid: 429 proves the request was refused before an email was accepted
#{:provider-rate-limited}
```

A connection failure does not prove that nothing happened. The Stripe adapter
may retry it because the provider's idempotency contract turns several asks
into one payment. The SendGrid adapter may not: the email could have been
accepted and only the answer lost, and another call could send another email.

This is why the retry predicate uses named reasons from the adapter rather
than generic exception classes. The question is not “was this an I/O error?”
It is “does this provider guarantee that repeating this operation is safe?”

The retry wraps both the HTTP exchange and interpretation of the response. A
policy around only the socket exception would retry a refused connection but
not a `503`, even though both mean the provider did not give a usable answer.

### Between attempts: durable failure, then deliberate surrender

A retry inside one call assumes the moment is bad. The outbox handles work that
must survive the process. Its relay records failures across separate passes.

A poison message is different again: it will fail today and tomorrow. If the
relay stops at it, everything behind it waits. If it retries forever, it spins.
After three failed relay passes, Lab 28 dead-letters the message and continues
with later work:

```text
pass 1   failed; remains pending
pass 2   failed; remains pending
pass 3   dead-lettered; later messages still proceed
```

Dead does not mean deleted. The original outbox row, attempts, last error,
timestamp, and EDN body remain available. An operator can fix the consumer and
explicitly revive the message. Revival is manual because a queue that revives
itself is only a slower infinite retry loop.

The body remains EDN so namespaced keys such as `:message/type` survive. A JSON
conversion that reduces that key to `"type"` would leave a plausible-looking
dead letter that cannot be replayed correctly.

## Incoming calls carry the same uncertainty

A webhook is an adapter driven by somebody else's delivery policy. The Stripe
endpoint performs these operations in order:

```text
raw bytes -> verify origin and freshness -> parse -> translate -> invoke module
```

The signature is HMAC-SHA256 over `timestamp.body`. It is compared with
`MessageDigest/isEqual`, and a valid but stale signature is rejected to limit
replay. Verification happens before translation because a parsed map has
already lost the exact bytes that were signed.

The response status is a control signal to the provider:

| request | response | reason |
|---|---:|---|
| unsigned, forged, or stale | `400` | retrying cannot repair it |
| valid and applied | `200` | complete |
| valid duplicate | `200` | already complete; a non-2xx would create a loop |
| valid event type not subscribed to | `200` | intentionally ignored |
| subscribed event in an unreadable shape | `500` | retain and retry while the integration is repaired |

Callbacks can arrive more than once or out of order. A provider event id is
claimed in an inbox, while payment state is the second guard against the same
fact arriving in a new envelope. Synchronous authorization and a later
callback can both discover success; a unique constraint on the payment's
outbox announcement lets both paths converge without a check-then-act race.

## Heterogeneous networks need translation, not wishful interfaces

Payments depends on `PaymentGateway`; Notifications depends on `Emailer`.
Stripe and SendGrid are named only by their adapters and the composition root.
Each port has an in-memory and an HTTP implementation, and the same contract
suite runs against both. A port with only one implementation would not yet
prove that it captured a stable application need rather than one vendor API.

The anticorruption layer is pure and directly unit tested because translation
can fail silently:

```clojure
{"id" "pi_1" "status" "succeeded"}
=> {:outcome :authorized :reference "pi_1"}
```

It has no optimistic default for unknown provider statuses, does not trust a
provider's echo of values the application originally sent, and distinguishes
an event type the application never subscribed to from a subscribed type whose
shape it can no longer understand.

The ports do not erase genuine differences. `PaymentGateway` promises
idempotency for a payment id. `Emailer` explicitly does not. A common interface
cannot manufacture a guarantee the far side does not provide.

## Testing the boundary

The suite follows the split introduced in Lab 21:

| test type | target | boundary treatment |
|---|---|---|
| pure unit | domain rules, ACL translations, signatures, retry predicates | values in and values out; no I/O |
| behavior / use case | module APIs and command handlers | real core with fakes for secondary ports |
| adapter / integration | module persistence and provider HTTP adapters | real Postgres and fake providers on real sockets |
| system / E2E | the inbound HTTP edge and complete module conversation | mostly Ring as a function; one Jetty socket smoke test |

The fake providers live in `dev/`, require no external accounts, and are less
forgiving than friendly mocks: fake Stripe requires an idempotency header and
deduplicates on it; fake SendGrid deliberately does not deduplicate repeated
sends. Tests observe
provider state rather than asserting private method calls, so implementation
refactoring does not invalidate the behavior contract.

`resilience_test.clj` names the network assumptions directly. It proves that
two `503`s can be absorbed, retries stop, one deadline bounds all attempts, an
open breaker stops wire traffic and later admits a recovered provider, and the
two adapters make different retry decisions. `dead_letter_test.clj` proves a
poison message does not block healthy work and can be revived.

## What remains uncertain

The lab makes uncertainty explicit; it does not eliminate it.

- There is no scheduler that revisits payments left `requested` or `pending`.
- There is no reconciliation job comparing local payments with provider truth.
- Bandwidth, large messages, back-pressure, and the economic cost of transport
  are not modelled.
- DNS changes, endpoint discovery, bulkheads, provider quotas, API version
  changes, and secret rotation need production policy.
- SendGrid delivery callbacks, bounces, refunds, and partial captures would add
  more long-running states and more provider-owned facts.

## What's next

The next reliability step for Payments is still reconciliation: periodically
ask the provider what it believes happened and converge local state. On whether
money moved, the payment provider is the authority. That gap remains open.

[Lab29](../lab29) takes a different step across the same boundary: it publishes
selected public changes to external subscribers with WebSub. The network
fallacies still apply, but now this system is the provider making delivery
promises to somebody else.

## What this lab inherits

Lab 28 keeps the earlier modular-monolith structure, Postgres ownership,
outbox/inbox delivery, telemetry propagation, and search projections. Those
features remain covered by their existing tests; they are not the new lesson.
The new lesson begins exactly where Lab 20 warned it would: at the remote effect
that cannot join the local transaction.

## Running it

```bash
bb check    # lint and formatting
bb test     # all tests; needs Docker
bb demo     # the failure scenarios; needs Docker
```
