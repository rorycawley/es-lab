# Build Spec: Event-Sourced Modular Monolith (Clojure + PostgreSQL)

A demo replicating Revolut's Postgres-as-event-bus architecture inside a single
vertically-sliced modular monolith.

**Read the "Gotchas" section before writing any code.** Several of the items there
are non-obvious and will produce a system that appears to work but is subtly wrong.

---

## 1. Goal

Demonstrate that a modular monolith on a single PostgreSQL instance can achieve:

| Requirement | Mechanism |
|---|---|
| 100% consistency (no dual-write) | Transactional Outbox — state + event in one transaction |
| At-least-once delivery | Reconciler polling loop |
| Millisecond latency | `LISTEN`/`NOTIFY` fast path |
| Idempotency | Inbox with unique constraint + `ON CONFLICT DO NOTHING` |
| Per-aggregate ordering | Partition key + advisory locks |
| Queryability | JSONB event payloads + GIN indexes |

### Non-goals
- Kafka, Debezium, or any external broker.
- Distributed transactions / 2PC.
- Snapshotting, event upcasting/versioning, or schema registry.
- Production ops (backup, HA, monitoring). Metrics are stubbed only.

---

## 2. Demo domain

Two modules, deliberately minimal:

**Module `accounts`** (event-sourced write side)
- Commands: `open-account`, `deposit`, `withdraw`
- Domain events: `AccountOpened`, `MoneyDeposited`, `MoneyWithdrawn`
- Invariant: balance may not go negative (enforced against rehydrated aggregate)

**Module `compliance`** (downstream consumer, CQRS read model)
- Consumes integration event `TransactionRecorded`
- Maintains `compliance.flagged_transactions` — any single movement > 10,000

The two modules share a process and a database but **never** touch each other's
schemas. Communication is exclusively: `accounts` → outbox → dispatcher →
`compliance.inbox`.

---

## 3. Stack

```clojure
;; deps.edn
org.clojure/clojure          {:mvn/version "1.12.0"}
com.github.seancorfield/next.jdbc {:mvn/version "1.3.955"}
org.postgresql/postgresql    {:mvn/version "42.7.4"}
com.zaxxer/HikariCP          {:mvn/version "6.2.1"}
com.stuartsierra/component   {:mvn/version "1.1.0"}
metosin/jsonista             {:mvn/version "0.3.13"}
org.clojure/tools.logging    {:mvn/version "1.3.0"}
;; test
clj-test-containers/clj-test-containers {:mvn/version "0.7.4"}
```

- **Driver: the standard `org.postgresql` pgjdbc.** Do NOT switch to `pgjdbc-ng`.
  It offers real async callbacks but is a non-standard driver; the polling
  workaround (see Gotcha #1) is fine and keeps the demo honest.
- **PostgreSQL 16+** (any version ≥ 9.5 works for `SKIP LOCKED`; 16 assumed).
- Migrations: plain numbered `.sql` files applied by a tiny runner. No Flyway.

---

## 4. Schema

Schema-per-module enforces isolation at the database level.

```sql
CREATE SCHEMA accounts;
CREATE SCHEMA compliance;
CREATE SCHEMA messaging;
```

### 4.1 Event stream (per module — shown for `accounts`)

```sql
CREATE TABLE accounts.event_stream (
  seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       UUID        NOT NULL UNIQUE,
  aggregate_id   UUID        NOT NULL,
  aggregate_type TEXT        NOT NULL,
  version        INT         NOT NULL,
  event_type     TEXT        NOT NULL,
  payload        JSONB       NOT NULL,
  metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- OPTIMISTIC CONCURRENCY. Without this, two instances handling commands for
  -- the same aggregate both append and silently corrupt the stream.
  CONSTRAINT uq_accounts_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_accounts_stream_agg  ON accounts.event_stream (aggregate_id, version);
CREATE INDEX idx_accounts_stream_type ON accounts.event_stream (event_type, seq);
CREATE INDEX idx_accounts_stream_gin  ON accounts.event_stream USING GIN (payload jsonb_path_ops);
```

This table is the **permanent audit record**. Nothing is ever deleted from it.

### 4.2 Shared outbox

```sql
CREATE TYPE messaging.msg_status AS ENUM ('PENDING','PROCESSED','FAILED');

CREATE TABLE messaging.outbox (
  seq             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        UUID        NOT NULL UNIQUE,
  source_module   TEXT        NOT NULL,
  event_type      TEXT        NOT NULL,
  partition_key   TEXT        NOT NULL,      -- usually the aggregate id
  payload         JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status          messaging.msg_status NOT NULL DEFAULT 'PENDING',
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ
);

-- PARTIAL index. Critical: without the WHERE clause the pending scan degrades
-- linearly as PROCESSED rows accumulate.
CREATE INDEX idx_outbox_pending
  ON messaging.outbox (partition_key, seq)
  WHERE status = 'PENDING';
```

The outbox is a **queue, not an archive**. Processed rows are pruned (§7).

### 4.3 Inbox (per consuming module)

```sql
CREATE TABLE compliance.inbox (
  seq             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        UUID        NOT NULL,
  event_type      TEXT        NOT NULL,
  partition_key   TEXT        NOT NULL,
  payload         JSONB       NOT NULL,
  status          messaging.msg_status NOT NULL DEFAULT 'PENDING',
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- THE idempotency guarantee. Redelivery is a no-op, not a duplicate.
  CONSTRAINT uq_compliance_inbox_event UNIQUE (event_id)
);

CREATE INDEX idx_compliance_inbox_pending
  ON compliance.inbox (partition_key, seq)
  WHERE status = 'PENDING';
```

### 4.4 Read model

```sql
CREATE TABLE compliance.flagged_transactions (
  event_id     UUID PRIMARY KEY,
  account_id   UUID           NOT NULL,
  amount       NUMERIC(19,4)  NOT NULL,
  direction    TEXT           NOT NULL,
  flagged_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
```

### 4.5 Notify trigger

```sql
CREATE OR REPLACE FUNCTION messaging.notify_outbox() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- Empty payload on purpose: it is a doorbell, not a delivery.
  PERFORM pg_notify('outbox_events', '');
  RETURN NULL;
END;
$$;

-- FOR EACH STATEMENT, not FOR EACH ROW. A 1000-row insert should ring the
-- doorbell once, not 1000 times.
CREATE TRIGGER trg_outbox_notify
  AFTER INSERT ON messaging.outbox
  FOR EACH STATEMENT
  EXECUTE FUNCTION messaging.notify_outbox();
```

---

## 5. Component map (Stuart Sierra `Component`)

```
:config          — reads EDN config, no deps
:datasource      — HikariCP pool                        [:config]
:migrator        — runs SQL migrations on start         [:datasource]
:accounts        — command handlers, aggregate logic    [:datasource :migrator]
:compliance      — integration-event handlers           [:datasource :migrator]
:event-router    — pure lookup: event-type -> [module]  [:accounts :compliance]
:dispatcher      — claims outbox, writes to inboxes     [:datasource :event-router]
:notify-listener — dedicated conn, LISTEN, kicks dispatcher [:config :dispatcher]
:reconciler      — scheduled poll, kicks dispatcher     [:dispatcher]
:inbox-workers   — one per module, drains inbox -> projections [:datasource :compliance]
:http            — Ring/Jetty, commands + queries       [:accounts :compliance]
```

`:notify-listener` and `:reconciler` are both just *triggers* for the same
`dispatcher/drain!` function. That is the key design property: the fast path and
the slow path share one code path, so the fast path cannot be subtly different.

`drain!` should be guarded so concurrent invocations within one JVM coalesce
(e.g. an `java.util.concurrent.Semaphore` with 1 permit and `tryAcquire`).

---

## 6. Core flows

### 6.1 Write path — one transaction, no dual write

```clojure
(defn handle-command [ds cmd]
  (jdbc/with-transaction [tx ds]
    (let [{:keys [id version]} (parse cmd)
          history      (load-events tx id)
          state        (reduce apply-event (initial-state) history)
          domain-evts  (decide state cmd)              ; pure
          integ-evts   (map ->integration-event domain-evts)]
      (append-events! tx id (count history) domain-evts)   ; UNIQUE(agg,version)
      (enqueue-outbox! tx integ-evts)
      {:ok true})))
```

If the transaction commits, both the state change and the notification exist.
If it rolls back, neither does. A `23505` unique violation on
`(aggregate_id, version)` means a concurrent writer won — retry the whole
command (reload, re-decide) up to N times.

### 6.2 Claim query — Phase 1 (no ordering guarantee)

```sql
UPDATE messaging.outbox o
SET    status = 'PROCESSED', processed_at = now(), attempts = o.attempts + 1
FROM (
  SELECT seq
  FROM   messaging.outbox
  WHERE  status = 'PENDING' AND next_attempt_at <= now()
  ORDER  BY seq
  FOR UPDATE SKIP LOCKED
  LIMIT  50
) AS claimed
WHERE  o.seq = claimed.seq
RETURNING o.*;
```

**Note `ORDER BY seq`, not `created_at`.** Timestamps collide and are not
monotonic under clock adjustment.

### 6.3 Claim query — Phase 3 (per-partition ordering)

Claim whole *partitions* under an advisory lock, not individual rows. The
`LIMIT` sits in a separate CTE, before the lock function is evaluated, which
avoids the volatile-function-in-`WHERE`-with-`LIMIT` trap.

```sql
WITH candidates AS (
  SELECT DISTINCT partition_key
  FROM   messaging.outbox
  WHERE  status = 'PENDING' AND next_attempt_at <= now()
  ORDER  BY partition_key
  LIMIT  20
),
locked AS (
  SELECT partition_key
  FROM   candidates
  WHERE  pg_try_advisory_xact_lock(hashtext(partition_key))
)
SELECT o.*
FROM   messaging.outbox o
JOIN   locked l USING (partition_key)
WHERE  o.status = 'PENDING' AND o.next_attempt_at <= now()
ORDER  BY o.partition_key, o.seq;
```

`pg_try_advisory_xact_lock` releases automatically at commit/rollback — no
cleanup path, no leaked locks on crash. Accept that `hashtext` collisions will
occasionally serialise two unrelated partitions; it is a throughput cost, not a
correctness one.

### 6.4 Dispatch — outbox and inbox in ONE transaction

Because both tables live in the same database, this is atomic. Do not split it.

```clojure
(defn drain! [ds router]
  (jdbc/with-transaction [tx ds]
    (let [msgs (claim-outbox! tx 50)]
      (doseq [m msgs
              module (router/targets router (:event_type m))]
        (insert-inbox! tx module m))          ; ON CONFLICT (event_id) DO NOTHING
      (mark-processed! tx (map :seq msgs))
      (count msgs))))
```

There is no window in which an event is marked processed but not delivered, and
none in which it is delivered but not marked. Retry the whole transaction on
failure; the inbox unique constraint absorbs the redelivery.

### 6.5 Listener component

```clojure
(defn listen-loop [pg-conn kick!]
  (with-open [st (.createStatement pg-conn)]
    (.execute st "LISTEN outbox_events"))
  ;; MANDATORY catch-up: notifications sent while we were disconnected are gone.
  (kick!)
  (let [pg (.unwrap pg-conn org.postgresql.PGConnection)]
    (loop []
      (when-not (Thread/interrupted)
        (try
          (when (seq (.getNotifications pg 30000))   ; blocking poll
            (kick!))
          (catch java.sql.SQLException e
            (log/warn e "listener connection lost; reconnecting")
            (throw e)))     ; supervisor reconnects and re-runs listen-loop
        (recur)))))
```

Supervise this in a loop with backoff. **Every reconnect must re-`LISTEN` and
then immediately `kick!`.**

### 6.6 Reconciler

Fixed-rate `ScheduledExecutorService`, every 10s. Calls the exact same `drain!`.
Nothing else. Its whole job is to make lost `NOTIFY` signals harmless.

Retry/backoff on failure:
```sql
UPDATE messaging.outbox
SET attempts = attempts + 1,
    last_error = ?,
    next_attempt_at = now() + (interval '1 second' * power(2, least(attempts, 10))),
    status = CASE WHEN attempts + 1 >= 20 THEN 'FAILED' ELSE 'PENDING' END
WHERE seq = ?;
```
`FAILED` rows are the dead-letter queue: excluded from the partial index
predicate, so they stop blocking their partition and stop costing scan time.

---

## 7. Retention

- `*.event_stream` — **never pruned**. This is the audit log and the ad-hoc-query
  surface.
- `messaging.outbox` — delete `PROCESSED` rows older than 24h on a scheduled job.
  (Revolut's own EventLog keeps 24h for the same reason.) High-churn table: set
  `autovacuum_vacuum_scale_factor = 0.01` on it via `ALTER TABLE ... SET (...)`.
- `*.inbox` — same, 24h.

---

## 8. Gotchas — read before coding

**#1 — pgjdbc has no async notification callback.** Per the official driver
documentation: *"A key limitation of the JDBC driver is that it cannot receive
asynchronous notifications and must poll the backend."* Consequences:
  - The listener needs a **dedicated connection created directly** via
    `DriverManager`/`PGSimpleDataSource` — **not** borrowed from HikariCP. A
    pooled connection will be returned, reset, and lose its `LISTEN`.
  - Use the **blocking** `getNotifications(timeoutMillis)` overload on a
    dedicated thread. The zero-arg version returns immediately and does no
    network I/O, so a naive `while(true) getNotifications()` loop spins forever
    receiving nothing.
  - A poll call does not detect a broken TCP connection. Add a periodic
    `SELECT 1` health check on the listener connection (e.g. every 60s) or you
    will listen to a dead socket indefinitely — the reconciler will mask this,
    which is exactly why it is easy to miss.

**#2 — Notifications arrive only after commit, and only between transactions.**
The Postgres docs are explicit that applications using NOTIFY for real-time
signalling should keep transactions short. Do not hold the dispatcher
transaction open while doing anything slow.

**#3 — `NOTIFY` is at-most-once.** If no session is listening, the signal is
discarded. This is not a bug to fix; it is why the reconciler exists.

**#4 — Duplicate payload folding.** Identical `(channel, payload)` pairs within
one transaction may be collapsed into a single delivery. With an empty payload
this is desirable — but it means you can never use the payload to carry the
event id, because you'd lose events.

**#5 — Payload limit is under 8000 bytes.** Non-issue here (empty payload), but
do not "optimise" later by embedding the event JSON.

**#6 — Isolation level must be READ COMMITTED.** `FOR UPDATE SKIP LOCKED` under
`REPEATABLE READ` or `SERIALIZABLE` raises serialization failures. Verify
`next.jdbc` isn't configured otherwise.

**#7 — Sequence gaps are real.** `seq` values are allocated *before* commit, so
row 5 can become visible before row 4. This is safe here because we track
per-row `status`, and unsafe if anyone later "optimises" to a high-water-mark
cursor (`WHERE seq > last_seen`). Add a comment in the code saying so.

**#8 — `SKIP LOCKED` alone gives no ordering.** Phase 1 explicitly has no
ordering guarantee. Do not claim otherwise in the README until Phase 3 lands.

**#9 — JSONB with next.jdbc needs explicit coercion.** Extend
`next.jdbc.prepare/SettableParameter` and `next.jdbc.result-set/ReadableColumn`
for `org.postgresql.util.PGobject`, or every insert will fail with
"column is of type jsonb but expression is of type character varying".

**#10 — Money is `NUMERIC`, never a float.** And decode it to `BigDecimal`,
not `double`, on the way out of JSONB.

---

## 9. Build phases

Each phase must be green before starting the next.

### Phase 1 — Correctness without latency
Transactional outbox + reconciler only. **No `LISTEN`/`NOTIFY` at all.**

Deliverables: schema, migrations, `accounts` aggregate + command handlers,
outbox enqueue, dispatcher `drain!`, `compliance` inbox + projection, reconciler,
HTTP endpoints.

Acceptance tests (Testcontainers, real Postgres):
1. Happy path: `deposit` → within 10s a matching row exists in
   `compliance.inbox` and, if > 10,000, in `flagged_transactions`.
2. **Rollback:** force the aggregate write to fail after the outbox insert;
   assert zero rows in *both* `event_stream` and `outbox`.
3. **Crash mid-dispatch:** kill the transaction between inbox insert and commit;
   assert the outbox row is still `PENDING` and redelivery produces exactly one
   inbox row.
4. **Idempotency:** insert the same `event_id` into the outbox twice by hand;
   assert exactly one `flagged_transactions` row.
5. **Concurrency:** 100 concurrent deposits to one account from 4 threads;
   assert final balance is correct and `event_stream` versions are 1..N with no
   gaps or duplicates.
6. **Poison message:** an event whose handler always throws reaches `FAILED`
   after N attempts and does not block other events.

### Phase 2 — Add the fast path
Add the trigger, `:notify-listener`, and the semaphore-guarded `drain!`.

Acceptance tests:
7. End-to-end latency p99 < 50ms (vs. seconds in Phase 1). Assert the improvement,
   not an absolute number.
8. **Kill the listener connection** mid-run (`pg_terminate_backend`); assert no
   events are lost and delivery continues via the reconciler.
9. **Disable the trigger entirely**; assert every Phase 1 test still passes.
   This proves the fast path is a pure optimisation.
10. Two JVM instances against one database: assert no event is delivered twice.

### Phase 3 — Ordering
Swap in the partition-claim query from §6.3. Same for the inbox worker.

Acceptance tests:
11. **Ordering:** 500 events across 20 aggregates, 8 concurrent dispatcher
    threads; assert that for every aggregate the inbox `seq` order matches the
    outbox `seq` order.
12. Assert throughput has not collapsed — different aggregates still process in
    parallel (measure wall time vs. a single-threaded run).

### Phase 4 — Demonstrate the "why not Kafka" argument
Read-only endpoints that show what a broker cannot do:
- `GET /audit/account/:id` — full event history, rebuilt on demand.
- `GET /audit/query?type=MoneyWithdrawn&min=5000&from=...` — ad-hoc SQL with a
  time range and a JSONB predicate over the whole event history.
- `GET /audit/replay/:module` — rebuild a read model from scratch by truncating
  the projection and re-running the stream.

---

## 10. Repository layout

```
deps.edn
resources/migrations/
  001_schemas.sql
  002_accounts_event_stream.sql
  003_messaging_outbox.sql
  004_compliance_inbox.sql
  005_compliance_read_model.sql
  006_outbox_notify_trigger.sql        # Phase 2
src/demo/
  system.clj                           # component system map
  config.clj
  db/  datasource.clj  migrate.clj  json.clj   # json.clj = Gotcha #9
  messaging/
    outbox.clj        # enqueue!, claim!, mark-processed!, mark-failed!
    inbox.clj         # insert!, claim!, complete!
    dispatcher.clj    # drain!
    listener.clj      # dedicated connection, LISTEN loop
    reconciler.clj    # scheduled kick
    router.clj        # event-type -> [module] (pure data)
  accounts/
    api.clj           # PUBLIC: commands + queries only
    domain.clj        # pure: decide, apply-event
    repository.clj
    events.clj        # domain -> integration event translation
  compliance/
    api.clj           # PUBLIC: handle-integration-event, queries
    projections.clj
  http/ routes.clj  server.clj
test/demo/
  fixtures.clj        # Testcontainers Postgres
  ...one ns per acceptance test group
```

**Isolation rule to enforce in review:** nothing outside `src/demo/accounts/`
may require anything from `demo.accounts.*` except `demo.accounts.api`. Same for
`compliance`. Consider a test that asserts this by scanning `ns` forms.

---

## 11. Attribution / source notes

The architecture mirrors Revolut's publicly described design (Revolut Tech,
*"Recording more events… But where will we store them?"* and *"Event Streaming:
The Revolut way"*): events persisted transactionally with state, `LISTEN/NOTIFY`
for the low-latency path, a reconciler that resends unpublished events older
than ~30s, and 24h retention on the log table. Their stated reasons for
rejecting Kafka were ad-hoc queries, querying by time, and guaranteed
consistency between state changes and persisted events.

Differences in this demo, all deliberate:
- Single process and single database, so outbox→inbox is one local transaction
  rather than a network hop. This removes a failure window Revolut has to handle.
- Advisory-lock partition claiming instead of their partitioning scheme.
- Two modules instead of ~160 services.
