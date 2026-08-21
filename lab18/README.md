# Lab 18: as-of queries

[Lab 1](../lab1) introduced two timestamps and argued that collapsing them "silently corrupts every question about *when*." Seventeen labs later, **nothing has ever asked such a question.**

This is that lab. The event envelope already carries the raw ingredients, but retained timestamps do not design the query for you. This lab makes both temporal questions explicit and shows where a plain event filter is safe—and where it is not.

## The same question, two right answers

The offline till from lab 1, made concrete:

```text
 1 Sep   loaded 10 vanilla, 5 chocolate     recorded the same day
 3 Sep   sold a vanilla                     recorded the same day
 3 Sep   sold another vanilla               the till was offline — recorded on the 6th
```

Now ask: **how much vanilla was on the truck on the 5th of September?**

```clojure
(as-known-on   log truck sep-05)  ;; => 9 vanilla
(as-happened-by log truck sep-05) ;; => 8 vanilla
```

Both are right. They are not the same question.

| | filters on | answers | asked by |
|---|---|---|---|
| **transaction time** | `:recorded-at` | *what did we believe on the 5th?* | auditors, support, anyone reconstructing a decision |
| **valid time** | ordinary `:event/occurred-at`, or an explicit correction `:effective-at` | *what do we now know was true on the 5th?* | reconciliation, stock counts, reporting |

A single timestamp can produce one of these and never the other. That is the whole of lab 1's argument, and it took until now to demonstrate it.

## One is stable, the other must not be

The property that decides which you want:

```clojure
;; three weeks later, another late sale from the 3rd arrives
(as-known-on later truck sep-05)   ;; => unchanged. Still 9.
(as-happened-by later truck sep-05) ;; => 8 becomes 7.
```

**Transaction history is immutable when the store assigns it authoritatively and does not permit backdating.** A fact committed after the 5th must not enter the 5th's transaction-time view. That is why `:recorded-at` comes from persistence, not from a client. Timestamp ties and clock behavior still make a stream version—or a safe global cursor for cross-stream questions—the more exact audit boundary.

**Valid time moves, and that is not a bug.** Learning something new about the 3rd is *supposed* to change what was true on the 3rd. The truck really did have seven.

For this example they agree at a cutoff after all the included facts were recorded. They need not agree universally: later corrections can remain effective in an earlier period. The gap between the two views is the gap between the domain history currently asserted and the knowledge available at an earlier transaction boundary.

## Corrections land on one axis and not the other

The till rang up vanilla; it was chocolate. The original sale occurred on the 3rd. The correction happened and was recorded on the 6th, but its effect belongs to the sale date:

```clojure
{:event/type        :sale-corrected
 :event/occurred-at sep-06
 :data {:sale-id      original-sale-id
        :from         "vanilla"
        :to           "chocolate"
        :effective-at sep-03}
 :metadata {:recorded-at sep-06}}
```

The sale id says exactly which immutable fact is being amended and lets the domain refuse a duplicate correction. The temporal query uses `:effective-at` for this event and `:event/occurred-at` for ordinary facts:

```clojure
;; asking about the 5th, after the correction is in the log
(chocolate believed)  ;; => 5   on the 5th we had not heard
(chocolate actual)    ;; => 4   we now know a chocolate cone went on the 3rd
```

The vanilla figure happens to match on both axes here — for entirely different reasons — which is a good reminder that agreement is not confirmation. Chocolate is where the correction shows.

The original sale remains in the log, unedited. [Lab1](../lab1#its-just-a-value) does not bend: new information accretes.

This lab's stock deltas are additive, and a correction is pinned to its sale's occurrence time; stream version breaks ties so the sale precedes its correction. That makes this effective-time projection well-defined. It is **not** a general aggregate-rehydration algorithm. Arbitrary state transitions depend on stream order, and removing or reordering historical events may create a sequence the aggregate never occupied. Use a purpose-built temporal projection for the question rather than passing a date-filtered subset to any aggregate fold.

## Prefer a version when you can

Both time-based cursors carry timestamp ambiguity — clock skew, ties, the boundary of a day in whose timezone. A version does not:

```clojure
(up-to-version log truck 3)   ;; the stream as it stood after its third event
```

If the question can be phrased as *"the state before event N"*, phrase it that way. It identifies an exact stream prefix. Wall-clock cutoffs still need a declared timezone and boundary convention. For cross-stream history, [lab19](../lab19) shows why a database sequence alone is not automatically a safe visibility checkpoint.

## Re-running a decision, and what that requires

`decide` is pure ([lab8](../lab8)), so feeding it all the same inputs reproduces its outcome exactly:

```clojure
(let [event    (nth stream 2)
      expected (get-in event [:metadata :decision-stream-version])
      rules    (get-in event [:metadata :rules-version])
      state    (state-at-version log truck expected replay)]
  (reconstruct decide original-command state rules))
;; => {:events [{:event/type :flavour-sold …}]}
```

The application retains the command's expected stream version with its facts. That is the input boundary. Inferring “state before event N” is safe only when N is the first fact from a known one-event decision; an arbitrary event can sit inside a multi-event append.

Note the last argument. **The rules are a version too**, and this is the uncomfortable part. The application records that version with the resulting facts, but the marker is only a pointer. Exact reconstruction also requires the original command and the executable old decider—source, configuration, feature flags, reference data and any other decision inputs—not merely the integer `1`.

The truck later starts holding two cones of each flavour back for pre-orders. Take a sale that was allowed under the old rules and re-run it under today's:

```clojure
(reconstruct decide cmd {:stock {"vanilla" 2}} 1)  ;; => {:events [...]}      allowed
(reconstruct decide cmd {:stock {"vanilla" 2}} 2)  ;; => {:refused "Reserved stock only"}
```

Same command, same state, different answer — because the rules moved. Re-running an old decision under current rules doesn't *explain* it; it replaces it with what you would do now, which is a different claim entirely and looks just as authoritative.

So "we can always explain any decision" is true only if the necessary audit inputs and historical behavior were retained. A causation id does not reconstruct the command body, and a command ledger may record only deduplication data. This lab supplies the original command explicitly rather than pretending the event log contains it.

`reconstruct` converts only named business refusals such as `:sold-out` into data. Unknown rule versions and unexpected failures propagate; reporting a programming error as “the business refused” would manufacture an explanation.

### Three versions, one rule

This completes a pattern the labs have arrived at three times:

| | what's versioned | so that |
|---|---|---|
| [13](../lab13) | the event schema | old events stay **readable** |
| [17](../lab17) | the fold's shape | known snapshot incompatibility is **detectable** |
| **18** | the decision rules | old decisions stay **explicable** |

Three axes move independently and need distinct compatibility or audit strategies. Their markers are not magic: event readers need supported schemas, snapshots need rebuildable source events and integrity checks, and a rules marker needs retained behavior behind it.

A change to the rules is the easiest of the three to miss, because most of the time the versions agree. Plenty of stock, and v1 and v2 give identical results — a test asserts that too. The divergence only appears at the margin, which is precisely where the contested decisions live.

## A caution about runtime

The implementations here are **investigative tools**, not production read paths: they scan and filter a stream from the beginning on every call. An aggregate snapshot caches one stream prefix and cannot directly answer arbitrary past cutoffs. A current-state projection also answers the wrong question unless it was deliberately designed with temporal history.

If as-of queries are a runtime requirement, build and test a bitemporal projection or database model for that use case. If they are rare support or audit tools, slower replay may be an acceptable trade-off. Architecture follows the requirement, not a universal ban.

## Testing the behavior

Pure domain tests call `decide` and `replay` directly for stock rules, correction invariants and strict historical semantics. Use-case tests enter through `application/handle` with an in-memory log plus fixed identity and time providers, then assert the recorded facts and metadata. Temporal-query tests compare public results on both time axes rather than internal calls.

A real persistence adapter needs integration tests proving `:recorded-at` is assigned authoritatively in the append transaction and that cursor/timestamp ordering behaves as documented. End-to-end coverage should be small and focus on wiring a late arrival and a retroactive correction through the actual intake and query adapters.

## What's next

So far the persistence examples have used immutable in-memory values. [Lab19](../lab19) runs the same domain against real Postgres and shows the visibility gap that opens when a position comes from a database sequence.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
