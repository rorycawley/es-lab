# Lab 34: a configurable process manager

[Lab 11](../lab11) built a process manager with four things hardcoded: a
`Duration/ofMinutes 30`, a `transfer-quantity`, the transition table as a
`defmulti`, and a `case` deciding which command each step issues.

[Lab 33](../lab33) settled the values. The transition table is the interesting
one, because lab 33's rule was *values, not structure* — and a state machine
is structure.

**The one idea: a process definition can be data because, unlike a rule, it
can be proved complete before it runs — and the moment it is data, an instance
must pin the version it started under, which makes changing a live process a
migration rather than an edit.**

```bash
bb demo    # no Docker; this lab is pure Clojure
```

```text
4. v2 ships: a sanctions hit now goes to a human
   Ada, pinned to v1, gets a sanctions hit    :rejected
   a newcomer on v2 gets the same hit         :awaiting-manual
```

Same event, two answers, and both correct.

## Why a state machine is lab 33's exception

Lab 33 refused predicates-as-data because a predicate needs an interpreter,
and an interpreter is a language with no type checker: `:ammount` is a valid
program that silently matches nothing forever.

A transition table needs no interpreter. It needs a lookup — `next-state` is
one `get` — and every way of getting it wrong is **decidable before anything
runs**:

```text
a transition to nowhere       :awaiting-identity on :identity-verified goes to
                              :awaiting-sanctionz, which is not declared
a step nothing leads to       :awaiting-documents is declared but unreachable
a step with no way out        :approved is declared but unreachable
terminal, and also not        :rejected is terminal and also transitions
a command nobody handles      :approved issues :open-acount, which no module handles
```

That last check reuses lab 29's derived routing table: a process that asks for
a command nobody handles is a step that silently does nothing. Five total
checks over a finite graph, and none of them needs an instance to find out.

That is the entire argument for letting this be a map. **Take the guarantee
away and the argument goes with it** — which is why there is no `:when` clause
anywhere in the shape. A guard is a predicate, and lab 33 settled predicates.

## The problem lab 33 did not have

A policy is instantaneous: change it, and only future events see it. A process
manager has instances **already running**.

Apply lab 33's test — *can changing it reach the past?* — to an instance's
fold, and the answer is yes. The same events under a new definition land in a
different state, or in one that no longer exists.

So the definition is not configuration to an instance. It is a **decision
input**, and it gets stamped:

```clojure
{:process/id         #uuid "…a1"
 :definition/version 1        ; pinned at start, never reassigned
 :state              {:status :awaiting-sanctions …}}
```

Exactly what lab 33's withdrawal does with the overdraft limit that permitted
it — one field, and it is the whole lab. `process/evolve` takes the definition
as a parameter and `instance/observe` is handed a *resolver*, a function from
version to definition, rather than the registry. There is no argument by which
either could ask what the current version is, and `architecture_test.clj`
fails the build if that changes.

Two consequences follow, and neither is a smell:

- **Two definitions run at once.** That is what it means for a process to take
  longer than the interval between releases.
- **A published definition is immutable.** Pinning to v1 guarantees nothing if
  v1 can be edited afterwards.

## The check a config file cannot perform

A definition is checkable alone. Publishing a *new* one is a different
question, because by then there are instances, and only the registry knows
both.

```text
publishing v2, which adds a step        fine — nobody can be in a state v1 never had
publishing v3, which removes one        refused, while anybody is standing in it
```

```text
instance …a3 is in :awaiting-identity under v1, which v3 does not declare
```

Nothing about v3 is wrong. It is wrong **now**, and no environment variable,
feature flag or YAML file can make that distinction, because none of them
knows who is in the room.

## A breaking change and its migration are one act

The two obvious orderings both deadlock, and finding that out was the most
useful thing this lab did:

- **publish, then migrate** — refused, because instances are stranded
- **migrate, then publish** — the instances now point at a version the
  registry does not have, so they cannot be resolved at all

That is not an accident of the implementation. It is the design saying
something true: you cannot ship a process that deletes a step without saying
where the people standing in it go, and *saying where they go* is not a
follow-up ticket that might slip. `registry/release` makes it one function, so
it cannot become two of which one is forgotten.

The mapping itself is not derivable:

```clojure
{:awaiting-identity  :awaiting-screening
 :awaiting-sanctions :awaiting-screening
 :awaiting-manual    :awaiting-screening}
```

Three of v2's waiting states collapse into one of v3's. Somebody who
understands the business decided that; no algorithm guesses it. What the code
can do is refuse a mapping that **forgets somebody** — a state with instances
in it and no instruction is a question nobody answered, not an omission to
default.

Three populations, and only one moves. Finished applications stay where they
are: they are over, and carrying them onto a process they already left would
imply they are not.

## What migration does not touch

An instance that went through `:awaiting-sanctions` went through it. v3 has
never heard of that state, and rewriting the recorded path to look like the
new process would be falsifying the record for tidiness. The move is appended
as its own fact instead:

```text
0a4's recorded path, after migrating   [:awaiting-sanctions :awaiting-manual]
and the move itself, recorded          [[:awaiting-manual :awaiting-screening]]
```

## What the hardcoded version cannot do

`engine/hardcoded.clj` is lab 0's move: the same process as a `case`, built
properly. It is shorter, a reader can follow it top to bottom, and **for a
process that never changes it is the better choice.** `contrast_test.clj`
starts by proving it gives identical answers.

Three questions it cannot answer:

- *what version is this instance running?* — there is no version
- *what steps does this process have?* — `grep`, and hope
- *is this definition complete?* — a `case` can be read by a person and not by
  a program, so nothing can check it, diagram it, or diff two releases

And one it answers badly. Changing that function changes every running
instance at once, retroactively and invisibly, because the state each is
sitting in is reinterpreted by whatever code happens to be deployed when it
next wakes up.

## Timeouts are still lab 33's problem

A duration is a value, and lab 33's question applies to it unchanged. Shorten
`:awaiting-manual` from seven days to one hour and an instance that entered
six days ago is suddenly overdue — same instance, same events, different
answer. Pinning covers it, because the duration lives in the definition and
the definition is pinned.

Time is an argument throughout, per lab 11. Nothing reads a clock, so the same
question asked twice gives the same answer forever, and
`architecture_test.clj` asserts it.

## What this lab is not

Not BPMN, not a workflow engine, not persistence, and not a visual designer.
There is no expression language in the definition, deliberately.

It also does not claim a definition should always be data. It claims the
question has a mechanical answer: if the shape can be checked exhaustively and
instances can pin a version, it may be; otherwise the `case` is fine, and
honest about what it is.

## What's next

Nothing in this sequence — lab 34 is the last one.

What this stops short of is the version where the definitions themselves are
an event stream: `ProcessVersionPublished`, `InstancesMigrated`, each with who
did it and why, which is where [lab 33](../lab33) ended up with its parameters
and for the same reasons. At that point publishing a process is a command with
its own invariants, the registry is a projection, and the configuration has
become a module with an aggregate — which is the argument closing on itself
for the second time.

## Running it

```bash
bb check    # lint and formatting
bb test     # 69 tests, no Docker
bb demo     # three applicants, three versions, one migration
```

## Sources

- **Jérémie Chassaing**, [*Functional Event Sourcing Decider*](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider) —
  the shape this process manager still has, and `isTerminal`, which becomes
  `:terminal true` in a declared state.
- **Ismael Celis**, [*The Decide, Evolve, React pattern*](https://ismaelcelis.com/posts/decide-evolve-react-pattern-in-ruby/) —
  `react` returning commands rather than performing effects, which is what
  lets a misconfigured process be refused by the aggregate it asks.
- [REFERENCE.md](../REFERENCE.md#which-of-them-may-be-configuration) collects
  the question of what may be configuration at all.
