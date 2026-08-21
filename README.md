# Event Sourcing Laboratory

A sequence of small labs that build up to an event-sourced system in Clojure, one idea at a time.

Influenced by [Do-It-Yourself: Event-Sourcing](https://www.youtube.com/watch?v=VSS_Q0Rf50E).

## How the labs work

Each lab is a self-contained Clojure project introducing exactly **one** idea, and the README carries most of it. Read the README, then read the tests to see the idea asserted.

The early labs are a single namespace of example data and the tests that pin its shape. From lab 6 the code does something, and from lab 8 it separates into a few small namespaces — domain, store, wiring — because keeping those apart is itself one of the things being taught.

The running example throughout is an Ice Cream truck selling flavours.

Labs build on each other in order, and each one links forward and back. Nothing is retrofitted: when a later lab changes a shape introduced earlier, it says so and explains what changed its mind.

## The labs

| Lab | Idea | Introduces |
|---|---|---|
| [1](lab1) | an event | `{:event/type … :data …}` — a fact that happened, modelled as a domain object |
| [2](lab2) | a command | `{:command/type … :data …}` — a request, addressed to one handler, that may be refused |
| [3](lab3) | an integration message | `{:message/type … :payload …}` — a fact crossing a boundary |
| [4](lab4) | identity | `:command/id`, `:event/id`, `:message/id` — three ids, three reasons |
| [5](lab5) | how many? | one command → 0..n events → 0..n messages |
| [6](lab6) | evolve | `(reduce evolve initial-state events)` — state is derived |
| [7](lab7) | streams and versions | `:stream/id`, `:stream/version`, optimistic concurrency |
| [8](lab8) | decide | `command → state → [event]`, and the read-fold-decide-append loop |
| [9](lab9) | projections | read models, `:event/position`, checkpoint and rebuild |
| [10](lab10) | a policy | `event → command` — the system reacting to itself |
| [11](lab11) | a process manager | `event → state → command`, spanning aggregates, reacting to time |
| [12](lab12) | publishing | the dual write, the outbox, and at-least-once delivery |
| [13](lab13) | schema evolution | upcasters, and when a change needs a new event type instead |
| [14](lab14) | compensation | undoing a step that already happened, and what that costs |
| [15](lab15) | deletion | append-only versus the right to erasure, and crypto-shredding |
| [16](lab16) | the aggregate boundary | which invariants must be immediate — measured, not asserted |
| [17](lab17) | snapshots | caching a fold, and the three ways it goes quietly wrong |
| [18](lab18) | as-of queries | two axes of time, and re-running a decision under its own rules |
| [19](lab19) | persistence | the same domain against real Postgres, and the gap it opens |
| [20](lab20) | outbox and inbox | delivering between modules, and what one database buys |
| [21](lab21) | functional core, imperative shell | ports, adapters, Component — and the first thing that runs |
| [22](lab22) | schema | validating at the edge, and why a schema is not a business rule |
| [23](lab23) | intentful endpoints | HTTP as a driving adapter; name the act, not the entity |
| [24](lab24) | authentication and authorisation | a real OIDC provider, and the four places authorisation lives |

**Labs 1–3 build the vocabulary.** Three shapes that look almost alike and mean entirely different things:

```text
COMMAND              please do this           may be refused
DOMAIN EVENT         this happened            already true, inside the domain
INTEGRATION MESSAGE  telling someone else     a contract across a boundary
```

Most of the difficulty in an event-sourced design is keeping these three straight. Collapsing any two of them is the usual way it goes wrong.

**Lab 4 gives each shape an identity.** Two vanilla sales are two facts but, without an id, one value — so `distinct` collapses them and a retry is indistinguishable from a third sale. All three shapes need an id, for three different reasons: the request's is minted by the sender so a retry can reuse it, the fact's by the domain, the delivery's afresh on every send. The lab also asks where ids come from — UUIDv4 versus the time-ordered UUIDv7, and why a function that reaches out for the clock or the RNG stops being testable.

**Lab 5 counts.** The first four labs show one command, one event, one message, which quietly implies matched sets. A command produces zero events (refused), one, or several; an event produces zero messages (the default), one, or several. The fan only ever goes one way.

**Lab 6 is the first behaviour.** `evolve` folds one event into state; `reduce` does the rest. State is never stored — it's recomputed from the events and can be discarded without loss. `evolve` also never refuses an event, because by the time an event exists it already happened; refusing is `decide`'s job, at a different moment.

**Lab 7 says which events to fold.** With two trucks in one log, "the events" stops being well defined — folding all of them returns a plausible number that answers a question nobody asked. `:stream/id` names the history; `:stream/version` numbers it, and offering the version you read back as a condition on the append is how two tills avoid selling the same last cone twice.

**Lab 8 closes the loop.** `decide` takes a command and the folded state and returns the events — zero, one, or several — and it is the only function permitted to say no, because it runs while the answer is still open. It returns *what happened*, not where it goes: the store stamps identity, stream, and version. The whole of an event-sourced write is then six lines.

```text
1. read    the stream        (lab 7)
2. fold    it into state     (lab 6)
3. decide  what happened     (lab 8)
4. append  at expected+1     (lab 7)
```

**Lab 9 adds the read side.** A projection is the same fold pointed at a different question — across the whole log rather than one stream, to look at rather than to decide with. `:event/position` orders the log globally so a read model can checkpoint and resume, and catching up incrementally is provably identical to rebuilding from zero, which is what makes read models disposable.

**Lab 10 makes the system react to itself.** A policy is the reactive rule between a fact and a request — *whenever a truck runs out, load more*. It routes rather than decides, so the business rules stay in `decide`; it derives its command id from the triggering event so an at-least-once redelivery is recognisable as a repeat; and it dedupes against the causation id already recorded in the log, needing no separate table of processed commands.

**Lab 11 gives the policy a memory.** A process manager is the decider shape once more — `state → now → [command]` — coordinating a multi-step transfer across two aggregates. Its history is a *correlation*, not a stream, because spanning aggregates is the whole reason it exists. It only ever issues commands, never writing facts itself, so even giving up is a command the aggregate decides on. And it is the first thing here that depends on *when* it is asked: a refusal records nothing, so a refusal and a lost message look identical from outside, and a deadline is the only way to tell either from "still working".

**Lab 12 sends the first integration message.** Recording a fact and announcing it are two writes to two systems that fail independently, and no ordering of them is safe — which is what the transactional outbox exists to fix. An event-sourced system turns out to have one already: the log is durable, ordered and positioned, so the relay is lab 9's projection pointed at a broker, and `store.clj` needed no changes at all. Delivery is at-least-once regardless, so the consumer deduplicates on the fact's `:event/id` rather than the envelope's `:message/id` — and there's a test showing what goes wrong if you pick the envelope.

**Lab 13 faces time passing.** You cannot migrate an event store, so every schema you have ever written must stay readable. Upcasters walk an old event to the current shape on read, chained one version at a time, so the domain contains no version numbers at all. The rule the lab is built around: changes to how a fact is *written down* get upcast; changes to what a fact *means* get a new event type — and it ships the wrong version too, to show that mislabelling a meaning change corrupts every historical total silently.

**Lab 14 undoes a step that already happened.** Give the truck a capacity and lab 11's transfer breaks in the interesting place: the donor has given ten cones up and the empty truck has no room for them. There is no rollback — an event is a fact — so compensation is a further *business* action with its own command and its own event, and the log ends up holding the attempt, the refusal and the undo. The fleet total is 49, then 39, then 49 again. Compensation can itself fail, and then a human is told rather than a retry loop spinning.

**Lab 15 erases a person from an append-only store.** Deleting the event isn't available — it puts a hole in lab 7's contiguous versions and replays a history that never happened, and there's a test showing exactly that. The first answer is structural: sales carry a customer id and nothing describing the customer, so erasing one never touches a sale. The residue that genuinely describes a person is sealed under a per-subject key, and erasure destroys the key — leaving `:personal/erased` where lab 13 left `:price/unknown`. What sold, and how much, is unchanged. The catch is that shredding does nothing for a projection built while the key still existed, which turns lab 9's disposable read models into a compliance requirement.

**Lab 16 asks where the boundary goes.** Lab 7 said a stream is the consistency boundary and moved on; this builds one domain three ways and counts the difference. One stream for the whole fleet enforces "the depot cannot go negative" and refuses four of five concurrent sales. One stream per truck refuses none — and cannot enforce the rule at all, silently, because nothing owns the depot. Giving the depot its own stream gets both: the invariant enforced by the smallest thing that owns it, contention only where it means something. The rule, and Vernon's four, fall out of the numbers. It also draws on the repository's own `archive/`, where ADR-0017 states the trade-off this lab measures.

**Lab 17 caches the fold.** The mechanism is four lines, so the lab is about how it fails. A snapshot changes cost and never answers — delete every one and nothing differs — which is what makes it safe to lose. It versions by the *fold*, not the event: change `evolve`'s state shape and every stored snapshot is wrong though no event changed, and `fnil` is precisely what turns that into silent garbage rather than a crash. Reading it in the wrong order double-counts. And because it's derived it belongs off the append's critical path. Lab 9 had already built the read-side version without naming it: a projection with a checkpoint *is* a snapshot.

**Lab 18 asks the log what was true last Tuesday.** It adds nothing to the store — the ability was a consequence of keeping events all along. A cone sold on the 3rd and recorded on the 6th makes "how much stock on the 5th?" two different questions with two different right answers: what we *believed* then (stable, what an auditor wants) and what we *now know* was true then (moves when late news arrives, which is the point). Re-running a past decision needs the state it saw **and the rules it ran under** — completing a pattern the labs reached three times: version the event schema so old events stay readable, the fold so old snapshots are detectably stale, the rules so old decisions stay explicable.

**Lab 19 makes it real.** Eighteen labs of pure functions meet Postgres 18, and `truck.clj` is copied from lab 8 *unchanged* — which is the claim the whole design was making. It isn't free: JSONB has no keyword type, so the first run failed with "Sold out" on a truck that had just been loaded. The fix the lab reached for was a hand-maintained coercion list; the fix it now carries is the rule that replaced it — **do not write a keyword into a stream** — with a test showing why decoding cannot save you, since `:key-fn keyword` restores keys and there is no equivalent for values. Two things move into the database: the version check becomes a `UNIQUE` constraint, so lab 16's contention can finally be *raced* rather than simulated; and the position becomes a sequence, which opens the gap labs 9 and 12 warn about. That gap is demonstrated here — a reader checkpoints past an event that hasn't committed yet and never comes back — along with the `pg_snapshot_xmin` fix and what it costs in latency.

**Lab 20 delivers between modules.** Lab 12 argued the transactional outbox against an in-memory vector, where the failure it prevents cannot occur; with lab 19's transactions the argument can be spent. A command's events, its ledger row and its outgoing messages commit together. Across a boundary the relay must publish and then mark, so at-least-once survives the outbox and the recipient needs an **inbox** — a dedupe record written in the same transaction as the effect, which is what lab 12's `:seen` set could not be once the effect moved outside the read model. And inside one database the move is a single transaction, so the modular monolith achieves exactly-once *delivery* — a thing lab 12 said nobody can build, true across a network and false across a schema.

## Reference

The labs stay small on purpose. [REFERENCE.md](REFERENCE.md) is where the detail lives — what belongs in an event's `:data` versus its envelope versus neither, which identity to use and who generates it, `recorded_at` and `global_position` and sharding, and what a stream can and cannot tell you. Read the labs first; reach for it when you're designing a real store.

**Lab 21 gives it a shape, and makes it run.** The repository had been doing functional core / imperative shell for twenty labs without naming it — `decide`, `evolve`, `react` and `announce` were always pure, and labs 4 and 11 had already argued that clocks and id generators are effects. This lab puts them behind four ports, two adapters and one Component system map, with the application layer reduced to *read, call core, write*. The boundary is not drawn but **asserted**: a fitness function reads the source and fails the build if the core requires a port, reaches for a clock, or if a conditional appears in the shell. One suite runs against both an in-memory store and Postgres, which is the only evidence a port is real. And `bb demo` prints a day in the life of the truck — the first thing in the repository that starts.

**Lab 22 validates at the edge.** Lab 2 drew the line between context-independent *validation* and context-dependent *business rules*, and nothing implemented the first half. Malli does — under the rule this repository's own archive states in a dependency comment: *schemas in the shell, never in the core*, because a schema describes data crossing a boundary and the core has none. The lab's spine is one assertion: a command can be perfectly well-formed and still correctly refused, and only one of those two answers can change without the command changing. Commands are validated **closed** (an unexpected key from a client is a bug); events are read **open** (a stream outlives its readers, which is lab 13's tolerant reads as a setting rather than a principle). It also adds the *driving* adapter lab 21 lacked, and replaces lab 19's hand-maintained coercion list with schema-driven decoding.

**Lab 23 puts it behind HTTP.** Reitit, ring and jetty appear in exactly two namespaces — the driving adapter and the composition root — and a fitness test fails the build if they spread. Routes name the *act*, Stripe-style: `POST /v1/sales`, not `PUT /v1/trucks/{id}`, because a PUT that changes status looks identical to every other PUT and carries no intent to audit or enforce. Lab 2's two kinds of rejection become **400** and **422**, with a test showing the 422 turning into a 200 once state changes while the 400 stays a 400 forever. A bidirectional check keeps the endpoint list and the command vocabulary the same list. And a ring handler being a function from a map to a map means the whole web layer is tested without a socket — bar one test that starts Jetty to prove there is one.

**Lab 24 asks who is calling.** It drives a real OIDC provider — `mock-oauth2-server`, which lives in `dev/` because an identity provider is a dependency of your tests and never of your application — and finds that authorisation is four things, not one. ADR-0020's layers land in four places the repository already built: role checks at the door beside the schemas, ownership inside `decide` where the state is, and response shaping in the query adapter. The two gates differ in *reachability*, which is the argument for having both: a policy issuing commands never passes the door, so a rule that matters has to live where nothing can go around it. The lab's best find is that **authority does not propagate** — a correlation id runs the whole length of a conversation, but the customer who bought the last cone did not authorise the restock it triggered, so the actor is stamped rather than inherited. Lab 1's warning about never storing a credential finally gets a test, lab 22's closed schemas turn out to be the thing stopping a client from naming its own actor, and the Postgres half of the suite catches JSON eating a keyword for the fourth time — in `:metadata`, which no previous fix had covered. That fourth occurrence is what finally produced the rule, from [andfadeev/clojure-event-sourcing](https://github.com/andfadeev/clojure-event-sourcing), whose codebase has the problem zero times and calls `keyword` twice, both on discriminators in their own columns. The rule then reached back through all twenty-four labs.

## Keeping it honest

Twenty labs written in sequence drift. A forward pointer outlives the lab it named; a docstring says *copied from lab 8, unchanged* about a file that has since changed; a prerequisites list written when only one lab needed Docker. Two manual audits of this repository found the same failure mode both times, and most of it was mechanically checkable.

```bash
bb audit      # consistency checks across every lab and document
bb test-all   # every lab's suite, in order
```

`bb audit` verifies that every link and heading anchor resolves, that each lab's *What's next* names the lab that follows, that a file claiming to be another lab's **still is it** (by diff, ignoring the docstring), that the table and summaries here cover exactly the labs that exist, that every lab needing Docker is declared, and that `payload` stays reserved for a message in transit, never an event's own `:data`.

The repository's discipline is *assert it in a test*. Its prose was the one thing not asserted; now it is.

## Running a lab

Each lab has its own `deps.edn`, `bb.edn`, and pinned toolchain in `mise.toml`, so labs can drift apart without breaking each other.

```bash
cd lab1
bb help     # prerequisites and available tasks
bb all      # setup, check, test
bb test     # just the tests
```

Prerequisites are [mise](https://mise.jdx.dev) and [babashka](https://babashka.org); `bb setup` installs everything else the lab pins.

[Lab 19](lab19), [lab 20](lab20), [lab 21](lab21), [lab 22](lab22), [lab 23](lab23) and [lab 24](lab24) use Docker — they run against a real Postgres in a container. Labs 1–18 are pure Clojure and need nothing running, and `cd lab24 && bb serve` starts the whole system on `:3000` in memory — with an identity provider beside it on its own port — and no Docker at all.

## `archive/`

An earlier, much larger attempt at the same destination: full-stack mini-projects with Docker Compose, Postgres, Angular, and a [roadmap](archive/ROADMAP.md) of fifty-odd projects. It is kept for reference and is not part of the lab sequence.

These labs are the restart — same goal, approached from the vocabulary up rather than the infrastructure down.
