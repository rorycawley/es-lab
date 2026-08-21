# Lab 19: persistence

Every store in the sequence so far was `[]`. Meanwhile [REFERENCE.md](../REFERENCE.md) spends pages on `bigserial`, `timestamptz`, `now()` versus `clock_timestamp()`, unique constraints and a visibility gap — with no lab behind any of it.

This lab is that evidence. It runs against a real Postgres 18 in a container, and it is where two things happen: a claim gets tested, and a warning gets demonstrated.

## The claim: the domain does not change

`truck.clj` retains the technology-independent decision model introduced in [lab8](../lab8). It is not handed a repository: the domain consumes and returns values while the application and store surround it. That is why the same model can run with an in-memory log first and PostgreSQL later without learning SQL.

```clojure
(defn handle [ds cmd]
  (application/handle ds truck-1 cmd gen-id occurred-at))
```

The application still performs lab 8's load, fold, decide, identify and append loop. It reads one exact history so the expected version matches the state used for the decision. Selling the last cone still produces two events; the fold still answers `{"vanilla" 0}`.

## The claim is not free, and this is the part that surprised me

Writing the tests, the first run failed with **"Sold out"** on a truck that had just been loaded.

The domain wrote `{:flavour :vanilla}`. JSONB has no keyword type, so `:vanilla` went in and `"vanilla"` came back, `evolve` built `{"vanilla" 1}`, and `decide` looked up `:vanilla` and found nothing. The domain was not wrong. The database was not wrong. **The encoding is lossy**, and something has to pay for it.

The adapter paid, with a hand-maintained list:

```clojure
(def ^:private keyword-valued #{:flavour :reason :reason-code})   ; deleted
```

That list is gone, and so is the failure, because the events no longer carry a keyword to lose. `:flavour` is a **string**, here and in every lab before this one. The fix was not a better decoder — it was declining to have the problem.

### Why the loss is one-way, and why decoding cannot save you

```clojure
(json/read-str (json/write-str {:flavour :vanilla}) :key-fn keyword)
;; => {:flavour "vanilla"}
```

`:key-fn keyword` restores **keys**, because a field name is known in advance — `:flavour` is in your schema whether or not any event uses it. There is no equivalent for **values**, and there cannot be: by the time you are decoding, a string is all there is, and nothing in the data says which strings used to be symbols. That asymmetry is the entire bug, and a test asserts it rather than describing it.

So the rule this repository now follows, taken from [andfadeev/clojure-event-sourcing](https://github.com/andfadeev/clojure-event-sourcing):

> **Do not write a keyword into a stream.** A keyword is a program symbol; a stored fact is data. The one keyword worth persisting is a **discriminator** the code branches on, and that belongs in a column of its own.

`:event/type` is exactly that discriminator, and it is exactly where the rule says to put it:

```sql
event_type TEXT NOT NULL          -- its own column, not inside data
```

```clojure
:event/type (keyword (:event-type row))   -- coerced once, at the point of dispatch
```

One `keyword` call, at the only place the code needs a symbol to dispatch on. Everything inside `data` is left as it was written.

### What that leaves of the encoding question

| encoding | round-trips Clojure values | readable in `psql` |
|---|---|---|
| JSONB, with an explicit JSON-shaped contract | for those modeled values | yes |
| JSONB + a coercion list | with a list somebody maintains | yes |
| EDN in a text column | losslessly, including keywords | no |
| Transit | losslessly | barely |

The first row is the one to want, and it is a **modelling** choice rather than an encoding one. It does not mean every Clojure value round-trips: UUIDs, instants, decimal semantics and namespaced keys require an explicit mapping. This adapter restores the known causation and correlation UUID fields at the envelope boundary; [Lab22](../lab22) generalises that residue with schemas.

Operational inspection and selective replay may legitimately query `data`, but recurring business reads usually deserve the projection [lab9](../lab9) introduced.

## Storage guarantees move into the database

**The version check becomes an atomic database compare-and-set.**

```sql
UPDATE stream_head
   SET stream_version = stream_version + :event_count
 WHERE stream_id = :stream_id
   AND stream_version = :expected_version
RETURNING stream_version
```

The insert branch creates a missing head only when the expected version is zero. This matters because `UNIQUE (stream_id, stream_version)` alone is incomplete: it rejects a stale writer that collides with an existing version, but a caller claiming a future expected version could otherwise create a gap. The head update and event inserts share one transaction; the event-table unique constraint remains a defense-in-depth integrity check.

[Lab16](../lab16) measured contention structurally because a vector cannot race. Here eight threads go at one real stream at once:

```clojure
(is (= 1 (count (filter #{:won} results))))
(is (= 7 (count (filter #{:lost} results))))
(is (= [1 2] (map :stream/version (store/stream ds truck-1))))
```

Exactly one wins, seven receive typed optimistic-concurrency conflicts, and the stream has no version gap or duplicate. These are not business refusals. The test also proves a future expected version is rejected for existing and brand-new streams.

An exact retry of an already identified append batch is idempotent: if every event id already names the same fact at the intended stream coordinate, `append` returns the recorded events. Reusing an id for different content is a distinct `:duplicate-event-id` error. Retrying the whole command handler is a separate concern because it must retain or deterministically reproduce the same identified batch; [lab20](../lab20) adds the command ledger needed for the zero-event case.

**The position becomes a sequence.** Which brings a problem a vector could not have.

## The visibility gap, demonstrated

Labs 9 and 12 and REFERENCE all warn about this. Here it is:

```clojure
(insert! a 1)      ; A takes position 1 and holds its transaction open
(insert! b 2)      ; B takes position 2
(.commit b)        ; and commits first

(store/since ds 0) ; => [2]      the reader sees only B, and checkpoints at 2

(.commit a)        ; A commits — position 1 lands *behind* the checkpoint

(store/since ds 2) ; => []       skipped, silently, forever
(store/since ds 0) ; => [1 2]    the event is right there. The reader passed it.
```

A `BIGSERIAL` is assigned at **INSERT** and becomes visible at **COMMIT**, and those are different moments. Nothing errors. Nothing logs. A projection is simply missing an event, and in [lab12](../lab12)'s relay it is a message that never gets sent.

### The fix, and what it costs

REFERENCE names three fixes; this implements the second — hold back anything an in-flight transaction might still be interleaving with:

```sql
AND xid < pg_snapshot_xmin(pg_current_snapshot())
```

`pg_snapshot_xmin` is the lowest transaction id still active in the current snapshot. PostgreSQL documents every lower xid as either committed and visible or rolled back and dead. Rows written by xids at or above that boundary are conservatively held back because an active lower xid may still publish an earlier sequence value.

```clojure
(store/since           ds 0)  ; => [2]   sees B, will skip A
(store/since-committed ds 0)  ; => []    holds B back — nothing is settled
(.commit a)
(store/since-committed ds 0)  ; => [1 2] both, in order
```

The trade is explicit and tested: **an event that is committed and perfectly readable is deliberately withheld.** Lag follows the oldest relevant assigned transaction id, so long-running write transactions can make the conservative window large. Whether that cost is acceptable depends on the workload and projection latency requirement.

A fourth test pins the case that makes it matter: with no overlapping transaction the two reads agree exactly, which is why this never shows up in development.

## What the schema says

Every column in [`resources/schema.sql`](resources/schema.sql) is argued for in REFERENCE, and the file cross-references where:

- `stream_head` — the atomic expected-version token; both stale and future claims fail
- `global_position BIGSERIAL` — assigned by the database authority, for resumption only ([lab9](../lab9)); rollback gaps are allowed
- `xid xid8 DEFAULT pg_current_xact_id()` — not domain data; the thing that distinguishes *committed* from *assigned and still in flight*
- `event_id UUID UNIQUE` — minted by the application before the write ([lab4](../lab4)); exact identified-batch retries return the original rows, while conflicting reuse fails
- `occurred_at` from the application, `recorded_at DEFAULT now()` from Postgres — [lab1](../lab1)'s two axes. `now()` is transaction-start time, not commit time; the batch shares it because the value is stable throughout one transaction.

Postgres 18 also has `uuidv7()`, which makes `DEFAULT uuidv7()` tempting in the DDL. The schema does not use it, for [the reason REFERENCE gives](../REFERENCE.md#who-generates-it--the-application-or-postgres): a database-generated id cannot survive an ambiguous retry.

## Testing the behavior and the adapter

Pure domain tests call `decide` and `replay` directly for stock rules and strict semantics. Behavior tests enter through `application/handle`; the real Postgres store is used here deliberately because persistence is the subject of the lab, while deterministic identity and occurrence time remain injected application effects.

Focused adapter tests prove the atomic head compare-and-set, stale and future conflicts, batch rollback, exact retry idempotency, JSON/envelope round-trips and database-owned transaction time. The visibility tests control two real connections and commit order. They are integration tests, not end-to-end tests: there is no HTTP or process wiring to smoke-test yet.

## Sources

- PostgreSQL 18, [Sequence Manipulation Functions](https://www.postgresql.org/docs/18/functions-sequence.html)—sequence allocation is not rolled back and gaps are expected.
- PostgreSQL 18, [System Information Functions](https://www.postgresql.org/docs/18/functions-info.html)—snapshot `xmin` and transaction visibility.
- PostgreSQL, [Date/Time Types](https://www.postgresql.org/docs/current/datatype-datetime.html)—`now()` means transaction-start time.

## What's next

Nothing, for now. The arc is complete: vocabulary, mechanism, reaction, evolution, failure, compliance, design, time, and finally a real store.

Except that lab 12's outbox argument was made against an in-memory vector, where the failure it prevents cannot happen. Now that transactions are real, [lab20](../lab20) spends them — and shows what a single shared database buys that a network cannot.

## Running it

This lab needs Docker. `bb test` sets the environment testcontainers wants on Rancher Desktop and colima.

```bash
bb all      # setup, check, test
bb test     # just the tests (starts a Postgres container)
```

The container is per-run and thrown away. These tests turn on transaction timing, so a shared database would make them lie.
