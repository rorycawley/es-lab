# Lab 31: performance is a claim you have to prove

Lab 30 deliberately stopped short of a latency claim. It proved that each
lookup rung had an index the PostgreSQL planner *could* use, but an index plan
is not an observation of how long a person waits.

**The one idea: performance is not a property visible in code or architecture;
it is a scoped claim about a workload, an environment, a metric, and a correct
answer, supported by an experiment that can fail.**

```bash
bb prove
```

The command runs two claims over the same held-out Registry journey:

```text
bounded compute             linear scan       -> prebuilt exact index
end-to-end architecture     12 boundary calls -> 1 batched boundary call
```

Both changes help. Neither makes the other irrelevant. The point is to locate
the limiting work and prove the effect at the boundary a user cares about.

## What the two source conversations actually say

Dan Luu's argument is narrower and more interesting than “AI makes all
software fast.” Coding agents make formerly expensive implementation work
cheap enough to try: a JIT, workload-specific regex machinery, multithreading,
or a custom index can become an afternoon experiment instead of a specialist
project. That changes the economics of small wins and makes software fitted to
an observed workload much more plausible.

The article also contains the qualification this lab spends. Its regex engine
overfit the benchmark until a holdout exposed it; some apparently suitable
queries became slower because another engine had an algorithmic optimization;
and Luu says current agents are poor at open-ended experimental design. The
cost of trying an implementation collapsed. The cost of establishing that its
result is correct, representative, and worth its other trade-offs did not.

The Hacker News discussion is not one rebuttal or one consensus. It points to
several different reasons software can still feel slow:

- a user journey may wait on a remote boundary many times;
- wasteful allocation and memory access can matter more than clever assembly;
- the relevant problem may be an architectural interaction rather than one
  bounded function;
- specialized code carries compatibility, operability, and maintenance cost;
- organizations may reward features and delivery rather than latency;
- a fast benchmark can still measure the wrong workload.

Those observations do not contradict cheap bounded optimization. They choose
a larger measurement boundary. Conversely, “the network dominates” is not a
reason to ignore server compute: adding 100 ms of avoidable work to an existing
100 ms round trip still doubles the wait. Both positions become useful after
the vague adjective *fast* is replaced by a falsifiable statement.

## A complete performance claim

“The indexed version is faster” leaves nearly every important noun unstated.
Lab 31 instead declares this before observing its samples:

| part | declaration |
|---|---|
| behavior | resolve registration numbers and preserve input order and misses |
| corpus | 20,000 retained Registry entities |
| proof workload | 12 exact lookups: 11 hits across the corpus and one miss |
| holdout | proof keys are disjoint from the keys visible while choosing the design |
| primary metric | median wall-clock time for the complete journey |
| diagnostic metric | p95 wall-clock time, nearest-rank |
| protocol | two warmups, seven paired trials, alternating which design runs first |
| environment | JVM, OS, architecture, processor count, heap limit and JVM arguments |
| correctness oracle | every design returns the same ordered values before timing starts |
| success | declared speedup, win-rate, and journey-budget thresholds all hold |

Seven trials are enough for a quick teaching experiment, not a publishable
universal result. The raw paired samples remain in the result so a surprising
summary can be inspected instead of trusted. A production proof would use a
pinned runner, more repetitions, representative data, realistic concurrency,
and retained evidence from repeated runs.

Correctness comes first for a reason. An optimizer that omits misses, changes
ordering, or returns fewer fields can win every timing trial while answering a
cheaper question. `prove.clj` refuses to begin measurement unless all four
journeys agree.

## Claim one: specialize the bounded compute

The baseline walks the retained corpus for every exact registration-number
lookup:

```clojure
(defn scan-many [entities registrations]
  (mapv #(scan-one entities %) registrations))
```

The candidate builds the access path once and performs direct lookups:

```clojure
(def index (into {} (map (juxt :registration-number identity)) entities))
(mapv index registrations)
```

This is Luu's own “build an index?” turn made deliberately ordinary. The best
optimization is often less work, not a heroic implementation of the same
work. It is also workload-specific: the index is useful because exact
registration lookup is a known, repeated operation.

Index construction is outside the measured journey. That is not a hidden free
lunch; it states the lifecycle being claimed. Registry maintains this
projection as entities change and serves many reads from it. If the job were a
single lookup over one newly received file, build time and memory would belong
inside the comparison and the result could reverse. A benchmark boundary is a
model of use, not a neutral choice.

The predeclared local claim requires a paired median speedup of at least 5x and
wins in at least six of seven trials. `bb prove` exits unsuccessfully when that
claim is not observed on the machine running it.

## Claim two: optimize the journey, not only its inner loop

The second experiment deliberately gives both designs the fast index. It adds
a controlled 10 ms wait to every crossing of a Registry gateway:

```text
chatty:  key -> wait -> lookup, repeated 12 times
batched: [key ...] -> wait -> [lookup ...], once
```

The model is synthetic so the input is controlled. It is not evidence that a
particular network has 10 ms latency. It proves the consequences *under that
declared boundary cost*, while `system_test.clj` supplies stronger structural
evidence that the two designs perform exactly twelve and one crossings. The
wall-clock samples include scheduler overshoot and all Clojure work around the
wait; they do not merely multiply twelve by ten and print the arithmetic.

The end-to-end claim requires the batched design to:

- achieve at least a 5x paired median speedup;
- win at least six of seven paired trials;
- meet a 60 ms median journey budget;
- demonstrate that the chatty baseline remains above 75 ms.

The absolute budget asks the product question—did the journey become fast
enough? The relative threshold asks the engineering question—did this change
cause a meaningful improvement? A ratio alone can celebrate 10 seconds
becoming 5 seconds; a budget alone can accept a noisy change that did nothing.

## The cohesive model: find the floor

For this deliberately simple journey:

```text
end-to-end time ~= local work + round trips × boundary cost + noise
```

The first experiment attacks local work and can improve it by orders of
magnitude. Put that already-fast lookup behind twelve sequential waits and the
journey still has a roughly 120 ms floor. The second experiment changes the
shape of the journey and removes eleven of those waits.

That is the synthesis:

1. Cheap agents make many more candidate optimizations economical.
2. A profiler and a workload tell them which bounded work is worth attempting.
3. An end-to-end decomposition prevents a locally impressive win from being
   mistaken for a user-visible one.
4. Correctness, holdouts, budgets, and repeated measurement turn generated
   code into evidence rather than a performance story.

Compute is not “free,” and architecture is not automatically the bottleneck.
The limiting resource can be CPU, memory bandwidth, allocation, disk, locks,
queues, remote latency, transfer size, rendering, or their interaction. The
method is invariant: name the observable, measure the whole path, decompose
it, change one relevant cause, and try to falsify the claim.

## Why the order alternates

Microbenchmarks on a managed runtime are vulnerable to JIT compilation,
garbage collection, caches, frequency changes, and other processes. The lab
warms both functions and then measures paired trials in this order:

```text
trial 0  baseline -> candidate
trial 1  candidate -> baseline
trial 2  baseline -> candidate
...
```

Pairing compares nearby observations under roughly the same conditions.
Alternating prevents the candidate from always receiving the warmer or cooler
position. The proof reports the median of the seven paired speedup ratios and
the fraction of pairs the candidate won; it does not infer a result by dividing
two hand-picked best times.

This is still not JMH and does not claim nanosecond-level JVM accuracy. The
local difference is intentionally large, and the end-to-end claim is measured
in milliseconds. A small inner-loop difference would require a proper
microbenchmark harness, more samples, allocation data, and likely hardware
counters. Choosing the tool follows the size and location of the claim.

## Tests and proofs have different jobs

```bash
bb test     # deterministic semantics, call counts, statistics and failure logic
bb prove    # real clock, real scheduler, scoped performance thresholds
bb all      # static checks, tests and the proof
```

The normal tests do not assert that one function completes before another on
every possible laptop. They prove that both implementations answer the same
question, the workload is held out, batching changes the crossing count, the
statistics are calculated as documented, and a missed threshold fails.

`bb prove` is the environment-sensitive evidence. A failure is information,
not a flaky test to weaken until green. First inspect the samples and runner;
then decide whether the implementation regressed, the environment changed, or
the original claim never generalized. Moving the threshold after seeing the
result would replace a proof with a description.

## What this lab does not prove

- The generated Registry corpus represents real names or query frequency.
- A 10 ms fixed wait represents the public internet, congestion, or tail
  behavior.
- A Clojure map is the right production index; Lab 30's PostgreSQL indexes have
  different build, memory, concurrency, and persistence properties.
- Seven local samples establish capacity under concurrent production load.
- Batching is free: real APIs must bound request size, memory, deadlines, and
  partial failure semantics.
- The specialized design is worth its maintenance cost.
- An agent found either optimization or can choose the correct experiment
  without human judgment.

The proof is intentionally narrow. Narrow evidence with visible assumptions is
more useful than a universal claim supported by a convenient benchmark.

## What's next

Take the same proof shape to the real Lab 30 module: retain an anonymized query
distribution, generate a representative filing corpus, measure each cascade
rung and fall-through, record index build and memory cost, exercise concurrency
and cold caches, and run from the geographies that use the service. Only that
evidence can set a production latency objective or decide whether a local
projection, batching, a different index, or less work is the right change.

## Sources

- Dan Luu, [“There's no reason for software to be slow anymore”](https://danluu.com/perf-opt/),
  especially the workload-specific optimization, holdout, overfitting, and
  experimental-design qualifications.
- The resulting [Hacker News discussion](https://news.ycombinator.com/item?id=49395628),
  which broadens the candidate bottlenecks to boundaries, memory behavior,
  architecture, maintenance, and incentives.
