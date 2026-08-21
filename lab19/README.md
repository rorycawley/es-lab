# Lab 19: persistence

Every store in eighteen labs was `[]`. Meanwhile [REFERENCE.md](../REFERENCE.md) spends pages on `bigserial`, `timestamptz`, `now()` versus `clock_timestamp()`, unique constraints and a visibility gap — with no lab behind any of it.

This lab is that evidence. It runs against a real Postgres 18 in a container, and it is where two things happen: a claim gets tested, and a warning gets demonstrated.

## The claim: the domain does not change

`truck.clj` is **copied from [lab8](../lab8), unchanged.** Not adapted, not parameterised, not handed a repository. It was written against an in-memory vector and it now runs against Postgres, because it was never told the difference.

```clojure
(defn handle [ds cmd]
  (let [history (store/stream ds truck-1)
        version (store/current-version ds truck-1)
        state   (truck/replay history)
        events  (truck/decide cmd state)]
    (store/append ds truck-1 version gen-id t0 cmd events)))
```

That is lab 8's four steps with `ds` where `log` used to be. Selling the last cone still produces two events; the fold still answers `{"vanilla" 0}`.

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
| JSONB, writing only what JSON can hold | yes | yes |
| JSONB + a coercion list | with a list somebody maintains | yes |
| EDN in a text column | losslessly, including keywords | no |
| Transit | losslessly | barely |

The first row is the one to want, and it is a **modelling** choice rather than an encoding one. Reach for rows three and four when you genuinely need to store values JSON cannot express — and notice that JSON still cannot hold a UUID, an instant or a decimal, which is a loss no amount of care avoids. [Lab22](../lab22) handles that residue with a schema, and draws the line between a loss you cannot avoid and one you chose.

And a reason to worry less about the second column than you might: if you find yourself querying `data` in SQL, that is a projection you have not built ([lab9](../lab9)).

## Two things move into the database

**The version check becomes a constraint.**

```sql
UNIQUE (stream_id, stream_version)
```

No `if` in the application. `append` inserts at `expected-version + 1` and translates SQLSTATE `23505` into the same `ex-info` every earlier lab threw. Which means [lab16](../lab16)'s caveat can finally be retired — it measured contention *structurally*, deterministically, because a vector cannot race. Here eight threads go at one stream at once:

```clojure
(is (= 1 (count (filter #{:won} results))))
(is (= 7 (count (filter #{:lost} results))))
(is (= [1 2] (map :stream/version (store/stream ds truck-1))))
```

Exactly one wins, seven are refused, and the stream has no gap and no duplicate. Not simulated.

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

`pg_snapshot_xmin` is the oldest transaction still open. Rows at or above it may yet be joined by a lower-positioned sibling, so they wait.

```clojure
(store/since           ds 0)  ; => [2]   sees B, will skip A
(store/since-committed ds 0)  ; => []    holds B back — nothing is settled
(.commit a)
(store/since-committed ds 0)  ; => [1 2] both, in order
```

The trade is explicit and tested: **an event that is committed and perfectly readable is deliberately withheld.** A reader lags the writer by the longest open transaction. That is the price of never stepping over a gap, and it is the right price — but it is a price, and a system with long-running write transactions will feel it.

A fourth test pins the case that makes it matter: with no overlapping transaction the two reads agree exactly, which is why this never shows up in development.

## What the schema says

Every column in [`resources/schema.sql`](resources/schema.sql) is argued for in REFERENCE, and the file cross-references where:

- `global_position BIGSERIAL` — assigned by the single writer, for resumption only ([lab9](../lab9))
- `xid xid8 DEFAULT pg_current_xact_id()` — not domain data; the thing that distinguishes *committed* from *assigned and still in flight*
- `event_id UUID UNIQUE` — minted by the application before the write ([lab4](../lab4)), so an ambiguous retry is idempotent rather than duplicated. There is a test for that.
- `occurred_at` from the application, `recorded_at DEFAULT now()` from the store — [lab1](../lab1)'s two timestamps, and `now()` rather than `clock_timestamp()` so a batch shares one value. Tested: the sale and the depletion have the same `recorded_at`, because they committed together.

Postgres 18 also has `uuidv7()`, which makes `DEFAULT uuidv7()` tempting in the DDL. The schema does not use it, for [the reason REFERENCE gives](../REFERENCE.md#who-generates-it--the-application-or-postgres): a database-generated id cannot survive an ambiguous retry.

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
