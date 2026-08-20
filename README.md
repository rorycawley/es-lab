# Event Sourcing Laboratory

A sequence of small labs that build up to an event-sourced system in Clojure, one idea at a time.

Influenced by [Do-It-Yourself: Event-Sourcing](https://www.youtube.com/watch?v=VSS_Q0Rf50E).

## How the labs work

Each lab is a self-contained Clojure project introducing exactly **one** idea. The code is deliberately small — often a single namespace of example data and the tests that pin its shape — because the point of a lab is the idea, and the README carries most of it. Read the README, then read the tests to see the idea asserted.

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

**Still to come:** publishing — getting a fact out of the log and into another module exactly once, when the log and the broker can fail independently.

## Reference

The labs stay small on purpose. [REFERENCE.md](REFERENCE.md) is where the detail lives — what belongs in an event's payload versus its envelope versus neither, which identity to use and who generates it, `recorded_at` and `global_position` and sharding, and what a stream can and cannot tell you. Read the labs first; reach for it when you're designing a real store.

## Running a lab

Each lab has its own `deps.edn`, `bb.edn`, and pinned toolchain in `mise.toml`, so labs can drift apart without breaking each other.

```bash
cd lab1
bb help     # prerequisites and available tasks
bb all      # setup, check, test
bb test     # just the tests
```

Prerequisites are [mise](https://mise.jdx.dev) and [babashka](https://babashka.org); `bb setup` installs everything else the lab pins.

## `archive/`

An earlier, much larger attempt at the same destination: full-stack mini-projects with Docker Compose, Postgres, Angular, and a [roadmap](archive/ROADMAP.md) of fifty-odd projects. It is kept for reference and is not part of the lab sequence.

These labs are the restart — same goal, approached from the vocabulary up rather than the infrastructure down.
