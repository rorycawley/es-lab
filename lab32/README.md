# Lab 32: Postgres as the event bus

Lab 12 introduced the outbox and lab 20 gave it an inbox. Both stopped at
correctness: a message is delivered at least once, and a duplicate is
harmless. Neither said anything about *when* it arrives, and both left the
delivery loop as a poll somebody would obviously replace with a broker later.

**The one idea: the low-latency path is an optimisation of a correct slow
path, not a second mechanism. `LISTEN`/`NOTIFY` and the reconciler both call
the same `drain!`, so removing the fast path costs latency and cannot cost an
event.**

```bash
bb demo    # needs Docker
```

```text
9. The doorbell, timed against the polling loop
   reconciler only (2s interval)      2012ms to the inbox
   with LISTEN/NOTIFY                 11ms
```

Everything else in the lab follows from taking that claim seriously enough to
test it: the whole Phase 1 suite is re-run with the trigger disabled, and has
to pass unchanged.

## The architecture being replicated

Revolut described a system with roughly 160 services on one PostgreSQL
instance, exchanging events through the database rather than through Kafka.
Their stated reasons for rejecting a broker were not performance. They were
ad-hoc queries, querying by time, and guaranteed consistency between a state
change and the event announcing it.

This lab is two modules instead of 160 services, in one process instead of
many, and every mechanism they described is here:

| Requirement | Mechanism |
|---|---|
| no dual write | transactional outbox — state and event in one transaction |
| at-least-once delivery | a reconciler that polls |
| millisecond latency | `LISTEN`/`NOTIFY` as a doorbell |
| idempotency | an inbox with a unique constraint |
| per-aggregate ordering | partition key plus advisory locks |
| queryability | JSONB event data and a GIN index |

## The domain changes, and the reason

Labs 0–29 sold ice cream and labs 30–31 searched a corporate registry. This
one runs two banking modules: `accounts` records money moving, and
`compliance` flags any single movement over 10,000.

The domain is not incidental. The invariant that makes an aggregate necessary
here — a balance may not go negative — has to be checked against a state
folded from history *inside the transaction that appends to it*, and money is
the case where getting that wrong is unarguable. It is also the case where
`NUMERIC` versus `double` stops being a style question.

## One transaction, and therefore no dual write

```clojure
(jdbc/with-transaction [tx datasource]
  (let [history  (repository/history tx aggregate-id)
        state    (domain/replay history)
        decided  (domain/decide state command)
        recorded (repository/append! tx aggregate-id (count history) decided ...)]
    (doseq [message (keep events/->integration-event recorded)]
      (outbox/enqueue! tx message))))
```

Read the first argument of `outbox/enqueue!`. It is `tx`, not a datasource,
and that single choice is the entire transactional-outbox pattern. A version
of that function taking a datasource would open its own connection and commit
separately — and then there is a window where the money moved and nobody was
told, or the reverse. There is no configuration flag for that failure mode.
There is just the type of the first argument.

`accounts_module` holds `INSERT` on `messaging.outbox` and nothing else, so
the producer cannot read the queue it writes to, and cannot take anything out
of it.

## The claim that had to be tested three ways

The reconciler and the listener are both *triggers* for `dispatcher/drain!`.
Neither contains delivery logic. If either knew something the other did not,
the fast path would stop being an optimisation and the two would need testing
separately.

`scenarios.clj` holds the Phase 1 properties as functions rather than tests,
so `fast_path_test.clj` can replay all of them under three configurations:

```text
no trigger, no listener      Phase 1, as it was
trigger, nobody listening    the doorbell rings in an empty house
trigger and listener         Phase 2
```

The middle one is not in the build spec and is the one worth having. `NOTIFY`
is at-most-once: if no session is subscribed when it fires, the signal is
discarded and nothing anywhere raises an error. That is not a defect to work
around — it is the documented behaviour, it is why the reconciler exists, and
it is the state every deployment passes through twice on every restart.

## Why the listener is fifty lines instead of five

The pgjdbc documentation is blunt: *"A key limitation of the JDBC driver is
that it cannot receive asynchronous notifications and must poll the backend."*
There is no callback. Three things follow, and each is a way to build
something that appears to work:

- **The connection cannot come from the pool.** `LISTEN` is session state, and
  a pooled connection is returned, reset and handed to somebody else. The
  subscription goes with it, silently — a session that is not listening looks
  exactly like a channel that is quiet.
- **The poll must be the blocking overload.** `getNotifications()` with no
  argument does no network I/O at all. A `while(true)` around it spins a core
  receiving nothing.
- **A timed-out poll cannot tell a quiet channel from a dead socket.** Nothing
  distinguishes "no deposits" from "the TCP connection died an hour ago", so a
  periodic `SELECT 1` is the only thing that will notice. This is the one most
  likely to be skipped, because skipping it appears to work: the reconciler
  keeps delivering, so a permanently dead listener shows up as latency nobody
  can explain rather than as an error anybody can see.

And the rule that ties them together: every reconnect must re-`LISTEN` **and
then immediately drain**. Signals sent while disconnected are gone, so a
listener that resubscribes without catching up has a hole in it exactly the
width of its own outage.

The doorbell carries nothing. Postgres may fold identical notifications within
one transaction into a single delivery, which is exactly what you want from a
doorbell and would lose events if it identified a particular row.

## Ordering costs something, and the cost is the interesting part

Phase 1 claims rows with `FOR UPDATE SKIP LOCKED` and makes **no** ordering
promise. Phase 3 claims whole partitions under `pg_try_advisory_xact_lock`, so
one account's movements are worked by one dispatcher in sequence order. The
demo runs the same workload through both:

```text
10. Ordering, with eight dispatchers on twenty accounts
    SKIP LOCKED (phase 1)         12 of 20 accounts delivered out of order
    partition lock (phase 3)       0 of 20 accounts delivered out of order
```

Both delivered every message exactly once. Only one of them can tell you what
happened first.

What the consumer side gives up for that is real and worth stating plainly: a
partition's messages are handled in one transaction, so if the third of five
throws, the first two roll back with it. Committing the first two and
dead-lettering the third would leave four and five free to be applied to an
account that never saw the third — which is the outcome the lock exists to
prevent. **In-order delivery and per-message failure isolation are not both
available**, and a system claiming both has quietly stopped providing one.
What does survive is isolation *between* partitions: a stuck account blocks
itself and nothing else.

## Three things a broker cannot do

Everything above is a reimplementation of what a message broker already gives
you. This is the other half of the argument, and it is the half Revolut
actually cited.

```text
GET  /audit/account/:id     full history, rebuilt on demand
GET  /audit/query           ad-hoc SQL: a time range and a JSONB predicate
POST /audit/replay/:module  drop a read model and rebuild it from the stream
```

None of them are clever. They are `SELECT` statements against a table that was
never pruned, and they are unavailable at any price from a log with a
retention window.

The retention asymmetry is the whole argument in one config entry:
`messaging.outbox` and `compliance.inbox` keep 24 hours; `accounts.event_stream`
has no retention setting and no code that could prune it. `replay_test` deletes
every outbox row — the exact state a broker with a 24-hour window is in the
next day — and rebuilds the read model anyway, because the facts were never in
the transport to begin with.

Replay is also where the privilege split shows its work. It takes three
identities:

```text
compliance   clears its own projection and its inbox
messaging    resurrects the outbox rows it still holds
accounts     re-derives messages from the stream and enqueues the rest
```

None of them could do another's step. The composition root is the only thing
that knows all three exist, which is exactly its job.

## Two bugs worth the space they take

Both were found by a flaky test, and both would have shipped.

**A predicate in a CTE is not re-checked when a row lock is granted.** The
partition claim selected its rows under the statement snapshot, so a second
claimer saw them as still pending, blocked on the row lock, and — because
READ COMMITTED re-evaluates only the *UPDATE's own* `WHERE` clause when the
lock is granted — went on to update rows already marked processed and return
them. Every message was handled twice. It was nearly invisible: the inbox and
the projection are both keyed on `event_id`, so the duplicate was absorbed and
the only symptom was an ordering test failing about one run in four. The fix
is `AND status = 'PENDING'` on the UPDATE itself, which looks redundant and is
the least obvious line in the lab.

**`ORDER BY partition_key LIMIT 1` serialises a scheme built for
parallelism.** Every worker picks the same lowest-keyed partition, one wins
the advisory lock, and the rest claim nothing and conclude the queue is empty.
It runs strictly one partition at a time, correctly, and looks like it is
working. The fix is to consider several candidates in random order and stop at
the first lock actually acquired.

Neither is a Postgres quirk you can look up when something breaks, because
nothing breaks. They are the reason `two-partitions-are-worked-at-the-same-moment-test`
uses a latch rather than a stopwatch: a fact about concurrency, not a number
about a laptop.

## What is deliberately not here

- **Snapshots, upcasting, a schema registry.** Labs 13 and 17 already have them.
- **An absolute latency number.** Lab 31 is an entire lab about why a latency
  claim needs a declared workload, environment and metric first. The
  assertion here is an *improvement* measured on the same machine in the same
  minute.
- **A doorbell on the inbox.** The listener kicks the dispatcher; the inbox
  workers poll. Giving the inbox its own trigger is the same trick applied
  twice, and the lab already makes the point once.
- **Two JVMs.** The multi-instance test runs two independently constructed
  dispatchers with their own semaphores and pools. That is what two
  deployments are, minus the process boundary — and the process boundary is
  not the hard part.

## Where this diverges from the build spec

Four places, all deliberate.

- **The event stream's column is `data`.** Lab 1 gave an event its `:data`;
  lab 3 gave a message in transit its `:payload`, and `bb audit` fails the
  build when the two words drift. The outbox and inbox columns keep the
  transport word, because transport is what they are.
- **Event types are qualified keywords** (`:accounts/money-deposited`), not
  `MoneyDeposited`. Twenty-nine labs of the other convention.
- **`POST /audit/replay/:module`, not `GET`.** A GET that truncates a table is
  a GET a link checker, a browser prefetch or an uptime probe can fire.
- **No jsonista.** Lab 19's store already had the `PGobject` coercion the spec
  asks for.

## Testing the boundaries

```text
architecture_test    module isolation by ns scan, no SQL in the shared transport,
                     the pure core stays pure, money never becomes a float
isolation_test       READ COMMITTED asserted; every privilege boundary probed
                     against the live database rather than reviewed
fast_path_test       every Phase 1 property, three configurations
ordering_test        500 events, 20 accounts, 8 dispatchers, zero inversions
retention_test       queues pruned, history and dead letters never
```

`isolation_test` is the one to read first. Schema-per-module is a convention
until the database enforces it, and every claim this lab makes about who may
touch what is asserted by trying it and expecting `42501`.

## Limits

Two modules is not 160, and one process is not a fleet. The thing this lab
does not have to solve is the interesting part of Revolut's problem: with
everything in one process, outbox-to-inbox is a local transaction rather than
a network hop, which removes a failure window they have to handle. The
dispatcher here cannot half-deliver.

The advisory lock hashes its partition key into a 32-bit space, so unrelated
accounts occasionally collide and serialise against each other. That is a
throughput cost and never a correctness one, which is the right way round —
but at Revolut's cardinality it would need measuring rather than assuming.

And the reconciler interval is the system's worst-case latency. Ten seconds is
a number chosen for a lab; the property that matters is that raising it costs
latency and never correctness.

## What's next

[Lab 33](../lab33) picks up a loose end from this one. `projections.clj` holds
`(def threshold 10000M)` — a business rule in the middle of a read model, and
one a regulator will eventually move. Making it configuration is obviously
right, and doing so uniformly is obviously wrong: the same edit that is safe
in a policy silently rewrites history in a fold. Lab 33 sorts the places a
rule can live by a single question — can a change to it reach the past?

What a real version of *this* lab needs next is operational rather than
architectural: a metric for outbox depth
and oldest-pending age, an alert on anything reaching `FAILED`, a way to redrive
a dead letter that is not an `UPDATE` typed by hand, and a measured answer to
what happens when one aggregate is hot enough that its retry budget runs out.
Lab 31's discipline applies to all four — each is a claim that needs a declared
workload before it means anything.

The failure this design cannot survive is losing the database, and it has no
answer for that beyond the ones Postgres already has. That is the trade being
made: a broker buys an independent failure domain, and this buys every question
you can ask in SQL.

## Running it

```bash
bb check    # lint and formatting
bb test     # 92 tests against real PostgreSQL 18; needs Docker
bb demo     # ten acts, from one transaction to ordering; needs Docker
```

## Sources

- Revolut Tech, [*Recording more events… But where will we store them?*](../Recording_more_events…_But_where_will_we_store_them.md)
  — the transactional log table, `LISTEN`/`NOTIFY`, the reconciler that resends
  anything unpublished, and 24-hour retention.
- The build specification is [`BUILD-SPEC-lab32.md`](../BUILD-SPEC-lab32.md),
  including the gotchas this lab spends most of its comments on.
- PostgreSQL documents [`NOTIFY`](https://www.postgresql.org/docs/18/sql-notify.html)
  (at-most-once delivery, payload folding, the size limit),
  [`SELECT ... FOR UPDATE SKIP LOCKED`](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE),
  [advisory locks](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS),
  and [what READ COMMITTED re-checks](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)
  when a blocked update is unblocked.
- pgjdbc on [listening for notifications](https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify),
  which is where the polling limitation is stated.
