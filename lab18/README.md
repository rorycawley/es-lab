# Lab 18: as-of queries

[Lab 1](../lab1) introduced two timestamps and argued that collapsing them "silently corrupts every question about *when*." Seventeen labs later, **nothing has ever asked such a question.**

This is that lab. It adds nothing to the store — every query here was answerable on the day [lab11](../lab11) was written, because the ability to ask what was true last Tuesday is a *consequence* of keeping events, not a feature you build.

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
| **valid time** | `:event/occurred-at` | *what do we now know was true on the 5th?* | reconciliation, stock counts, reporting |

A single timestamp can produce one of these and never the other. That is the whole of lab 1's argument, and it took until now to demonstrate it.

## One is stable, the other must not be

The property that decides which you want:

```clojure
;; three weeks later, another late sale from the 3rd arrives
(as-known-on later truck sep-05)   ;; => unchanged. Still 9.
(as-happened-by later truck sep-05) ;; => 8 becomes 7.
```

**Transaction time is immutable.** What you believed on the 5th cannot change, whatever arrives afterwards — which is exactly why an auditor asks for it. "Why did you refuse that application?" is answerable only against what you knew at the time.

**Valid time moves, and that is not a bug.** Learning something new about the 3rd is *supposed* to change what was true on the 3rd. The truck really did have seven.

Once everything has arrived, the two agree. The gap between them is the gap between the world and your knowledge of it, which is a real thing and worth being able to measure.

## Corrections land on one axis and not the other

The till rang up vanilla; it was chocolate. That correction *occurred* on the 3rd and was *recorded* on the 6th:

```clojure
;; asking about the 5th, after the correction is in the log
(chocolate believed)  ;; => 5   on the 5th we had not heard
(chocolate actual)    ;; => 4   we now know a chocolate cone went on the 3rd
```

The vanilla figure happens to match on both axes here — for entirely different reasons — which is a good reminder that agreement is not confirmation. Chocolate is where the correction shows.

And the original sale is still in the log, unedited. [Lab1](../lab1#its-just-a-value) does not bend: new information accretes.

## Prefer a version when you can

Both time-based cursors carry timestamp ambiguity — clock skew, ties, the boundary of a day in whose timezone. A version does not:

```clojure
(up-to-version log truck 3)   ;; the stream as it stood after its third event
```

If the question can be phrased as *"the state before event N"*, phrase it that way. It's exact, and it is what the next section needs.

## Re-running a decision, and what that requires

`decide` is pure ([lab8](../lab8)), so feeding it the state it saw reproduces its outcome exactly:

```clojure
(let [state (state-before log truck 3 replay)]     ; the truck as it was
  (reconstruct decide original-command state 1))   ; the rules as they were
;; => {:events [{:event/type :flavour-sold …}]}
```

Note the last argument. **The rules are a version too**, and this is the uncomfortable part.

The truck later starts holding two cones of each flavour back for pre-orders. Take a sale that was allowed under the old rules and re-run it under today's:

```clojure
(reconstruct decide cmd {:stock {"vanilla" 2}} 1)  ;; => {:events [...]}      allowed
(reconstruct decide cmd {:stock {"vanilla" 2}} 2)  ;; => {:refused "Reserved stock only"}
```

Same command, same state, different answer — because the rules moved. Re-running an old decision under current rules doesn't *explain* it; it replaces it with what you would do now, which is a different claim entirely and looks just as authoritative.

So "we can always explain any decision" is true only if you kept the decider. Most systems don't, and discover it during an audit.

### Three versions, one rule

This completes a pattern the labs have arrived at three times:

| | what's versioned | so that |
|---|---|---|
| [13](../lab13) | the event schema | old events stay **readable** |
| [17](../lab17) | the fold's shape | old snapshots are detectably **stale** |
| **18** | the decision rules | old decisions stay **explicable** |

Three axes, moving independently, each needing its own version marker. And each fails the same way when you skip it: quietly, with a plausible answer.

A change to the rules is the easiest of the three to miss, because most of the time the versions agree. Plenty of stock, and v1 and v2 give identical results — a test asserts that too. The divergence only appears at the margin, which is precisely where the contested decisions live.

## A caution about runtime

Young permits a date-limited query but notes that a production system generally should not be doing this. These are **investigative tools**, not runtime features: they fold a stream from the beginning on every call, and none of the machinery from [lab9](../lab9) or [lab17](../lab17) helps, because a projection is built for one question and a snapshot caches one point.

Runtime reads go to projections. As-of queries are for the support call, the audit, and the reconciliation — where being slow and exactly right is the requirement.

## What's next

Nineteen labs of pure functions, and not one of them has met a database. [Lab19](../lab19) runs the same domain against a real Postgres, and shows the gap that opens when a position stops being an index.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
