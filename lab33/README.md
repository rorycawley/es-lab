# Lab 33: rules by configuration

[Lab 32](../lab32) hard-coded a reporting threshold:

```clojure
(def threshold 10000M)
```

That is a business rule sitting in the middle of a projection, and it will
change. Making it configuration is obviously right, and doing so uniformly is
obviously wrong — the same edit that is safe in one place silently rewrites
history in another.

**The one idea: a rule may be configured exactly where changing the
configuration cannot reach the past. Everywhere else the parameter is a
decision input and belongs in the event — and the tidiest way to record it is
a stream of its own.**

```bash
bb demo    # no Docker; this lab is pure Clojure
```

```text
1. evolve, reading configuration — forbidden
   balance under the old fee            35998
   balance under the new fee            35994
```

Four events, unchanged. A closed account's balance moved because somebody
edited a file.

## The question that sorts everything

Not *is this rule stable?* — nothing is. **Change the configuration, and ask
whether an answer about the past changes.**

| Location | Configurable? | Because |
|---|---|---|
| `evolve` | **never** | replay under new config disagrees with live state, and nothing detects it |
| `decide` / `isTerminal` | parameters only, **stamped** | the decision must stay reproducible, so what it read goes in the event |
| policies | **yes — the best home** | output is a command the aggregate validates anyway; forward-only |
| projections | yes for current views | rebuildable; as-of views need the parameter's own history |

`reach_test.clj` is that table as a test. Each row is a probe run under two
configurations, asserting whether the answer moves — and each of the other
test namespaces is one row worked out in detail.

## Never: `evolve`

`engine/evolve.clj` is the tempting version, built properly for lab 0's
reason: an argument against something is worth having only if you can run it.
It differs from `account.clj` by one expression.

```clojure
;; account.clj — the fee comes from the fact
:money-withdrawn (update state :balance - (+ (:amount data) (:fee data)))

;; engine/evolve.clj — the fee comes from a file
:money-withdrawn (update state :balance - (+ (:amount data)
                                             (rules/parameter config :withdrawal-fee)))
```

The fee is a business parameter that changes twice a decade. Every instinct
says put it in configuration. What it costs is that the same stream folds to a
different balance depending on a value that is not in the stream — and not for
new events, for an account closed six years ago.

Three things make it worse than an ordinary bug:

- **Nothing throws.** You get a plausible number. The only way to notice is to
  have written the old one down.
- **There is no version to compare.** Schema versioning ([lab13](../lab13))
  handles events whose *shape* changed. Nothing here changed shape.
- **Lab 17's fold version cannot help**, because the fold's code is identical.
  A snapshot taken under the old fee still validates.

So `decide` reads the fee once, at the moment of the decision, and writes it
into the event. `evolve` takes it from the fact and is deterministic for as
long as the facts exist.

## Conditional: `decide`, if it stamps

`decide` may use a configured parameter. What it may not do is use one and
forget.

Both parameters arrive as fields on the command rather than from a registry —
Chassaing's external-command-becomes-internal-command enrichment, and the same
move [lab 8](../lab8) makes by having `decide` return proposals instead of
writing them. Keep the function total in its arguments and let the edge do the
reaching. `architecture_test.clj` fails the build if `account.clj` ever
requires `lab33.rules`.

Then both get recorded, in different halves of the event:

```clojure
{:event/type :money-withdrawn
 :data       {:amount 300M :fee 2M}                 ; part of what happened
 :metadata   {:rules {:overdraft-limit 500M}}}      ; why it was permitted
```

That split is [REFERENCE.md](../REFERENCE.md#where-does-each-fact-go)'s rule,
not a preference. A domain expert would say the fee is money that left the
account. Nothing about the limit *happened* — it is a decision input, which is
what [lab 18](../lab18) says you must retain to re-run a decision and get the
same answer.

Leave it out and there is no bug to find. Every function is correct; the
information required to reach the right answer is simply not in the system,
and next year's audit re-runs the withdrawal against next year's limit and
reaches a different verdict. That presents as a discrepancy, not as a defect.

## The best home: policies

[Lab 10](../lab10) already contains the reason, written for another purpose:
*a policy owns the reaction; `decide` protects the target.* Two properties
follow.

**Its output is a request.** Misconfigure the sweep to 900 on an account
holding 100 and the policy dutifully asks; the savings account's `decide`
refuses on its own authority and the history is untouched. The blast radius of
a wrong number is a refused command.

**It is forward-only.** Changing it alters no recorded fact, so there is
nothing to replay and nothing to disagree with — the property `evolve` cannot
have.

There is one trap, and it only appears once the policy is configurable. Lab 10
derives command ids so that at-least-once redelivery is recognisable as a
repeat. That derivation must use the policy's **name and the triggering
event**, and never a configured value:

```clojure
(UUID/nameUUIDFromBytes (.getBytes (str policy-name "/" event-id) "UTF-8"))
```

Fold the swept amount into it and reconfiguring silently breaks deduplication:
the redelivery of an old event produces a different id, matches nothing, and
the account is swept twice. One word, no symptom, until the day somebody edits
a number.

## Conditional: projections

Rebuildable by construction, so reconfigure-and-rebuild is the ordinary way to
change one. The catch is that **rebuild and reclassify are the same
operation**, and whether that is correct depends on the question:

```text
what is reportable now?         today's threshold      flagged
what was reportable in March?   March's threshold      flagged-as-of
```

Both are legitimate. Building only the first and using it to answer the second
is how a compliance report for a closed year quietly changes — the demo raises
the threshold in June and watches a January transaction stop being reportable.

The as-of version cannot be built from configuration at all. It needs the
parameter's own history, which is the next section.

## Configuration as a stream of its own

A threshold change is a business fact. Somebody decided it, at a time, for a
reason, effective from a date that is usually not the date it was entered.
Every one of those is a field, and a file on disk has nowhere to put any of
them.

```clojure
{:event/type :parameter-changed
 :data {:parameter :reporting-threshold  :value 15000M
        :effective-from #inst "2026-06-15"
        :changed-by "regulator"  :reason "statutory instrument 2026/114"}}
```

Four problems stop being problems: as-of resolution is a fold prefix; who
changed it and why is on the fact rather than in a commit message; a decision
can stamp a version instead of copying values; and the "configuration" is a
projection, so [lab 9](../lab9)'s rules apply to it.

`rules/stream.clj` is an `evolve` over parameter changes — the same fold
[lab 6](../lab6) introduced, applied to the rules themselves. It reads no
configuration because it *is* the configuration, which is the recursion
terminating rather than a paradox.

It also puts [lab 18](../lab18)'s two axes in the smallest system where they
diverge. A change entered on Friday effective from the first of the month is
backdated, and *what was the threshold in March* is a different question from
*what did we know in March*. A correction effective in February changes
February's answer and leaves March's alone, because a later change already
superseded it.

## Values, not structure

The line that decides whether any of this stays manageable.

```clojure
{:reporting-threshold 15000M}                    ; a value.    Accepted.
{:flag-when [:and [:> :amount 10000M] …]}        ; structure.  Refused.
```

The second needs an interpreter, and an interpreter makes the configuration a
programming language — one with no type checker, no tests, no code review and,
once it lives in a database, no `git blame`.

`engine/predicate.clj` builds one, twenty-five honest lines, because the
failure worth showing is not that it is hard. It is that it works:

```text
reads back as                    (amount > 10000 and direction = "debit")
matches a 900,000 debit          true
one letter wrong, same movement  false
```

`:ammount` is a valid program. `(get fact :ammount)` is nil, the comparison is
false, the rule matches nothing forever, and the compliance report comes back
empty — which looks exactly like a quiet month. Note which mistakes the
interpreter *does* catch: unknown operators and malformed rules, both
structural. The one it cannot catch involves a name, and business rules are
made of names.

`rules.clj` is what keeps it out, and it is fifteen lines of `clojure.core`:

```clojure
(def parameters
  {:reporting-threshold #'decimal?
   :overdraft-limit     #'decimal?
   …})
```

The map is the schema, and it is closed — an unknown key is refused, and so is
a value of the wrong shape. Every declared predicate tests one scalar, so no
parameter can hold a nested rule. `architecture_test.clj` asserts that
property directly, because the check only closes anything while it holds.

Vars rather than function values, incidentally: a var is callable, so nothing
is lost, and `#'decimal?` carries its own name so a refusal can say what was
expected rather than only what was wrong.

## No libraries, and everything deterministic

`deps.edn` names Clojure and nothing else. Reaching for a schema library to
police the shape of configuration would make the argument in prose and
contradict it in the dependency list — and it would hide the check behind an
abstraction when the point is that you can read it in one sitting.

Purity and determinism are load bearing rather than tidy. A lab whose claim is
*this answer changed and configuration is why* has to be able to re-run
anything and get a byte-identical result, or it cannot tell you which
difference the configuration caused. No clock, no `random-uuid`, no mutable
state, and `architecture_test.clj` asserts all three.

## What this lab does not say

It does not say configuration is good. It says configuration is safe in one
place, conditional in two, forbidden in one, and that the difference is
mechanical rather than a matter of judgement.

It also does not build a rules engine beyond the counter-example, a hot
reloader, or an admin UI — and it has no opinion on where the configured
values are stored, because the storage is not what makes any of this correct
or incorrect.

## What's next

[Lab 34](../lab34) takes the one case this lab could not settle. A threshold
is a value and a state machine is structure, so lab 33's rule says a process
manager's transition table stays in code — and that turns out to be too
strong. A state machine earns an exemption because, unlike a predicate, it can
be **proved complete before it runs**: every transition lands somewhere, every
step is reachable, none is a dead end, and every command it issues is one
somebody handles. Five total checks, no interpreter.

The catch is the one this lab never had to face. A policy is instantaneous, so
changing it reaches nothing; a process manager has instances already running,
and folding their events under a new definition lands them somewhere else — or
somewhere that no longer exists. So the definition gets pinned and stamped
exactly like the overdraft limit above, and changing a live process becomes a
migration rather than an edit.

Beyond that, the build this lab stops short of: parameter changes as a real
stream in the database of [lab 32](../lab32), approved through a command with
its own invariants — a threshold change is a decision somebody makes, and it
has rules of its own about who may make it and how far in advance. At which
point the configuration has become a module, with an aggregate, and the
argument closes on itself.

## Running it

```bash
bb check    # lint and formatting
bb test     # 54 tests, no Docker
bb demo     # one threshold change, and everywhere it reaches
```

## Sources

- **Jérémie Chassaing**, [*Functional Event Sourcing Decider*](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider) —
  the four-part Decider, `decide` staying pure, and enriching an external
  command into an internal one before it reaches the domain.
- **Ismael Celis**, [*The Decide, Evolve, React pattern*](https://ismaelcelis.com/posts/decide-evolve-react-pattern-in-ruby/) —
  names the third step, which is where this lab puts most of its configuration.
- [REFERENCE.md](../REFERENCE.md#where-the-business-rules-live) collects the
  question of which places may hold a rule at all.
