# Lab 26: telemetry

Twenty-six labs have been building one kind of record: the event log, which says what the business did and keeps it forever. This lab adds the other kind, which says what the machine did while doing it and throws it away in a fortnight.

**They are not the same record, and the mistake in both directions is expensive.** Put operational detail in the event store and it stops being readable by the people whose language it was written in. Put business facts in the telemetry pipeline and your reporting silently depends on a sampler.

[REFERENCE.md](../REFERENCE.md#layer-3--not-in-the-event-store) has been holding this lab's thesis in three lines since before there was a lab for it:

> Pod name, handler class, SQL timings, exceptions, stack traces. These go to logs and traces. The correlation id is the **bridge** between the two worlds. Don't merge them.

```bash
bb demo     # starts a real Postgres with Testcontainers
```

```text
  One trace. Two modules. Two transactions. Nothing shared.
  ──────────────────────────────────────────────────────────────

  trace d923282041f43a93fae800434b57e730
  catalog change-price               accepted
    log  command_id=be850df8… correlation_id=8bd130ab… price_cents=300
    catalog publish-price-changed      completed
      log  fact_id=5011ae8f… message_id=2e5d8101…
      ordering catalog-price-changed     accepted
        log  fact_id=5011ae8f… message_id=2e5d8101…
```

## Lab 25 left a placeholder in the shape of this

Every slice in [lab 25](../lab25) was already wrapped in a cross-cutting behaviour that recorded what happened:

```clojure
(behaviour/observation audit :catalog/change-price)
```

It appended a keyword to an atom. That is not useless — tests still assert on it — but it answers exactly one question, from inside the process, for as long as the process lives. `behaviour/telemetry` sits beside it now and answers the questions you actually have at three in the morning, and the seam it plugs into did not have to change to accept it.

## Three signals, one context

OpenTelemetry has three of them and they are not competing formats:

```text
LOGS     what happened here      one event, with detail
TRACES   what happened where     one request, across processes
METRICS  what happens usually    aggregates, cheap, no detail
```

The reason to run them through one SDK is the fourth thing, which is not a signal: **context**. A log line emitted inside a span carries that span's trace and span id automatically, so a log and a span become two views of one event rather than two piles you join by timestamp.

```clojure
(is (= (:trace-id span) (:trace-id log)))
(is (= (:span-id span)  (:span-id log)))
```

That is `telemetry_test.clj`'s first assertion, and it is the whole argument for bridging your logging library into the SDK rather than replacing it with something OpenTelemetry-shaped.

## Keep the logging library. Bridge it

OpenTelemetry Java deliberately publishes **no logging API for applications**. The reasoning is that every codebase already has one, logging is the one cross-cutting concern nobody wants to migrate, and an appender can carry what you already write into the new pipeline. clj-otel says the same thing in `log-record.clj`, which is marked *for use by logging libraries only*.

So Logback stays, and one appender does the bridging:

```xml
<appender name="OTEL"
          class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
  <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
</appender>
```

`OpenTelemetryAppender/install` in the composition root connects it to the configured SDK. Until that call runs, log statements go to the console and nowhere else — which is a real failure mode, because nothing errors and telemetry is simply missing.

## A log line is a map, not a sentence

The other half of "good logging" has nothing to do with OpenTelemetry:

```clojure
;; the line you have to write a regex for, months later
(log/info (format "price changed to %d for %s" price-cents product-id))

;; the line the backend can filter on
(telemetry/log! "change-price accepted" {:price-cents 300 :product-id id})
```

The second arrives at the collector as attributes with types. `telemetry_test.clj` asserts `es.price_cents` comes back as the **number** `300`, and that the message body does not contain `300` at all. Attribute names are a small contract of their own: OpenTelemetry wants dotted, snake_cased strings like `http.response.status_code`, clj-otel derives them from namespaced keywords, and `platform/telemetry.clj` is the one file that knows this. It is also why the prefix is `es` and not `lab26` — a digit gets snake_cased into `lab_26`, which is the kind of paper cut a seam exists to absorb once.

## One trace across a boundary that was built to decouple

Lab 25's whole point was that Catalog and Ordering share no transaction. Catalog commits a price, a ledger row and an outbox message together, and *later* a relay publishes and Ordering claims the fact in its own transaction. Three separate commits, deliberately.

A trace puts them back together without putting them back in one transaction. The mechanism is the W3C `traceparent` header, injected on one side and extracted on the other:

```clojure
(context/->headers)                  ; {"traceparent" "00-<trace>-<span>-01"}
(context/headers->merged-context h)  ; adopt it as the parent
```

The interesting decision is not how, but **when**. Trace context has to be captured in the transaction that changed the price, so the outbox row carries a column for it:

```sql
INSERT INTO catalog.outbox
  (message_id, message_type, fact_id, causation_id, correlation_id,
   traceparent, ...)
```

Mint it at relay time instead and it names the relay's trace, and every consumer's trace answers *what did the background worker do* rather than *what happened to my request*. That is [lab 12](../lab12)'s argument for freezing the message with the write, applied to the context that explains it. Two assertions hold the line:

```clojure
(is (str/includes? (:outbox/traceparent row) (:trace-id command)))
(is (str/includes? (:outbox/traceparent row) (:span-id command)))
```

The message contract itself is untouched. A delivery is `{:headers … :message …}`, which is the shape every broker already has, and lab 25's closed `PriceChanged` map is still exactly the map lab 25 wrote. A transport concern did not get to widen a business contract.

### Where this stops working

The chain here is one message, one subscriber, so the consumer span can be a child of the producer span and everything lands in one trace. **A batching relay cannot do this.** One relay span cannot be the child of forty different producers, and a trace that stays open for however long a queue is backed up is not a trace anyone can read. At that point trace context becomes a *link* — the consumer starts its own trace and points at the producer's span — and you navigate between traces instead of within one. The mechanism is the same header. What changes is the claim you are making about causality.

## The trace id is not the correlation id

They coexist in this lab, in the same outbox row, and they answer different questions:

| | `correlation_id` | `traceparent` |
|---|---|---|
| means | this belongs to that business conversation | this belongs to that request's execution |
| lives in | the inbox, the ledger, the orders table | one outbox row, until it is published |
| retained | as long as the business keeps records | days, and only if sampled |
| complete | always | only for sampled requests |

`REFERENCE.md` advises seeding a correlation id from your trace id, so you can pivot between the two worlds. That is good advice and it is not permission to alias them. Sampling drops the trace and keeps the fact; three years later *why did this refund happen?* has to be answerable from the record that survived. `telemetry_test.clj` checks the `ordering.inbox` table's columns and asserts `correlation_id` is there and `traceparent` is not.

## A refusal is not an error

[Lab 2](../lab2) separated context-independent validation from context-dependent business rules; [lab 23](../lab23) turned that into **400** and **422**; [lab 22](../lab22) insisted that concurrency and infrastructure failures must not masquerade as business outcomes. The same line lands here, on span status:

```text
catalog change-price      status=UNSET  outcome=malformed
ordering place-order      status=UNSET  outcome=price-unavailable
catalog change-price      status=ERROR  outcome=-  recorded: exception
```

A client sending a zero price is a request the system answered correctly. Marking that span `ERROR` puts it in the same bucket as a database that has stopped responding, ruins the error-rate number every alert is built on, and wakes somebody up for a typo. So the outcome is an *attribute* — a small closed vocabulary the slices already return — and `ERROR` is reserved for the machine failing.

The third line above is real: a duplicate outbox message id makes Postgres reject the insert, the whole command rolls back, and that span is genuinely an error with the exception recorded on it.

## Telemetry is a data-protection surface

This is the one that costs money.

[Lab 15](../lab15) sealed a personal field under a per-subject key so that destroying one key erases one subject from a history that cannot be edited. [Lab 24](../lab24) asserted that a credential is never stored. Both of those are defeated by one log line, because the copy in a telemetry backend is outside the store those labs control, on a vendor's retention schedule, and no amount of crypto-shredding reaches it.

So `place-order` now carries a customer email. It is legitimate — a receipt has to go somewhere — and Ordering stores it. The design answer is that attributes are an **allow-list**, written per slice, never derived:

```clojure
(behaviour/telemetry
 :ordering/place-order
 {:attributes #(select-keys % [:order-id :correlation-id :product-id :quantity])})
```

Deriving them from the request would be shorter and would export the email the moment somebody added it. `redaction_test.clj` then checks the whole pipeline — every span name, attribute key, attribute value, event, log body and log attribute — for the address, including on the refusal and malformed paths, which is where this usually breaks: somebody attaches the request to the error so it can be debugged.

It also asserts the string `customer` appears nowhere, because a field *name* tells a reader where to go looking.

## Metrics count machine behaviour, not business facts

One counter, deliberately:

```clojure
lab26.slice.requests{es.module, es.request, es.outcome}
```

That answers *how often is this refused today, and did it change after the deploy*. It does not answer *how many prices changed*, and it must not be asked to. Telemetry is sampled, buffered, dropped under load and aggregated into buckets — every one of those is a feature there and a defect in a business number. The event log already answers business questions exactly, and [lab 9](../lab9)'s projections are how you make that cheap.

## Where the library is allowed to be named

[Lab 23](../lab23) confined reitit, ring and jetty to two namespaces and failed the build if they spread. An observability library needs that rule more than a web framework does, precisely because it is useful everywhere:

```text
platform/telemetry.clj   how telemetry is produced   (clj-otel-api, slf4j)
system.clj               where telemetry goes        (clj-otel-sdk)
```

Four fitness tests hold it: nothing else names `steffan-westcott`, `io.opentelemetry`, `org.slf4j` or `logback`; the two that do take one half each; nothing under `src/` names the in-memory collectors that only tests need — the same rule [lab 24](../lab24) applied to its identity provider; and `place_order.clj` does not name telemetry at all, so [lab 0](../lab0)'s pure `price-order` still cannot acquire a reason to.

This is a case where the seam earns its keep rather than a rule that every library needs a wrapper. Lab 25's advice — start procedural, refactor under evidence — still stands. The evidence here is that OpenTracing and OpenCensus both looked permanent, and that a pure function which can emit a span will eventually want to.

## Testing telemetry rather than admiring it

The repository's discipline is *assert it in a test*, and telemetry is usually exempted from it — you look at a dashboard and decide it seems fine. `opentelemetry-sdk-testing` supplies in-memory span, log and metric collectors, and the SDK is configured with unbatched processors, so a span is readable the moment it ends and no test sleeps.

`dev/recorder.clj` turns the SDK's Java objects into ordinary Clojure maps, and every claim above becomes an assertion. The suite is `telemetry_test.clj` (what the pipeline produces), `redaction_test.clj` (what it must never produce) and `architecture_test.clj` (where the library may be named) — beside lab 25's slice, pricing and database-boundary suites, which are unchanged apart from the delivery shape.

## Deferred

A bound-context-aware Logback appender. clj-otel has one — `clj-otel-adapter-logback`, with a matching `CljOtelMdcAppender` that would remove the manual MDC handling in `platform/telemetry.clj` — but it is unreleased at 0.2.10, so this lab uses OpenTelemetry Java's own appender. That is correct here because every slice is synchronous and `with-span!` sets the *current* context, which is what the standard appender reads. Asynchronous code is where the difference starts to matter.

An OTLP exporter and a collector: the SDK configuration is already a parameter, so pointing `system/start-telemetry!` at a real backend is a config change rather than a code change. HTTP server spans and an inbound `traceparent` from a client — [lab 23](../lab23) has the adapter, and the inject/extract mechanism is the one above. Sampling policy, which is where the cost conversation actually happens. Span links for a batching relay. Exemplars joining a metric back to a trace. Runtime and JDBC auto-instrumentation via the Java agent, which would add spans this lab did not write. Log severity and levels, which are a whole subject and not one this lab's assertions depend on.

## What's next

Telemetry describes work the system already knows how to do. [Lab27](../lab27) adds work it does not: a search box, implemented in the Postgres that is already there.

The connection to this lab is closer than it looks. A search index is a projection — derived, disposable, rebuildable — so it inherits lab 9's rules and lab 17's fold-version problem, where the text search configuration turns out to be the fold version. It also inherits this lab's: the query string is the one input a user fills with anything at all, including their own email address, so it never becomes a span attribute. And `outcome-of` gains two words, which is all it takes for the counter above to start answering *what fraction of searches find nothing* — the metric a search feature lives or dies by.

## Running it

```bash
bb check    # lint and formatting
bb test     # slice, telemetry, redaction, architecture and boundary tests
bb demo     # the trace above, and the three things it is not; needs Docker
```
