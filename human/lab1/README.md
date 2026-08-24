# Lab 1: an act

The output of this lab is **two functions** — `act?` and `unanswered`. The six values in [`src/registry/act.clj`](src/registry/act.clj) are examples of what those two say about six candidates; they are not themselves the point.

> **An act is something the registry authoritatively did, under a statutory power, that asserts, recognises, determines or brings about a legal fact.**

The [numbered lab sequence](../../lab1) asks what a domain event is, on an ice cream truck, where a modelling mistake is annoying. This track asks the harder version on a statutory registry, where a modelling mistake is **unrecoverable** — not expensive to fix, but impossible, because the information was never captured.

The case is Case 1 from [Worked Examples](../README_EXAMPLES.md): a surname mistyped on registration in 2019, discovered and rectified seven years later. Nothing about the ownership of the land ever changed. Only the record was wrong — for seven years, and demonstrably so.

---

## The one test

For anything you are about to record:

> **Could this be read aloud in court as an account of what the registry did, and why it was entitled to do it?**

If yes, it is an act. If it reads as a description of a data change, you have modelled the mechanism and lost the meaning.

The filter is authority: **no statutory power exercised, no act.** That is `act?`, and it is deliberately the weakest check that could work:

```clojure
(defn act? [candidate]
  (some? (:act/power candidate)))
```

It cannot tell you the provision is the right one. It can only tell you somebody was made to name one — and that turns out to be most of the value, because `NotificationSent`, `AddressValidated` and `PaymentReceived` cannot answer the question at all.

Six candidates, and the answers are not the ones a database schema would give:

```text
registration-of-transfer    ✓  power exercised; the register changes
rectification-of-register   ✓  a different power, and appealable
─────────────────────────────
folio-row                   ✗  true, and not evidence
field-change                ✗  no power to change a name in isolation
notification-sent           ✗  operational — the consequence already occurred
official-search             ✗  assertion — the registry reported; it decided nothing
regime-deployed             ✗  audit — authority over the system, not the register
```

`official-search` is the interesting one. It is neither an act nor operational, and it is the artefact a two-store model loses — the only proof of what the purchaser was actually shown. That is what [C10](../README.md) exists for.

---

## The thirteen questions

An act is a legal artifact, and legal artifacts have a fixed set of questions that must be answerable about them. These are not a schema — they are the questions a lawyer would ask in a witness box. **The schema is derived from them, not designed.**

```clojure
(def questions
  {:q1  :act/type          ; What was done?
   :q2  :act/subjects      ; To what was it done?
   :q3  :act/actor         ; Who did it?
   :q4  :act/delegation    ; Under what delegation?
   :q5  :act/instance      ; At whose instance?
   :q6  :act/power         ; Under what power?
   :q7  :act/regime        ; Under what regime?
   :q8  :act/basis         ; On what evidence?
   :q9  :act/effective-at  ; When did it take effect?
   :q10 :act/recorded-at   ; When was it recorded?
   :q11 :act/priority      ; What legal priority applied?
   :q12 :act/decision      ; What was decided?
   :q13 :act/effect})      ; What did it change?
```

A map, not a validation library. A schema says *these keys are required*; this says *which legal question each key answers*, and the difference matters six months later when someone deletes a required key because nothing reads it. The key they delete will be Q4 — the answer to whether *this officer* was entitled to exercise the power, which is what decides whether the act was void.

### `some?`, not `contains?`

The one-word decision in the lab, and it is load-bearing:

```clojure
(defn unanswered [act]
  (into (sorted-set)
        (remove #(some? (get act (questions %))))
        (keys questions)))
```

Every act must **explicitly resolve** all thirteen. "Not applicable" is a valid answer where a regime genuinely does not apply — a rectification fixes no priority, and a corporate registry has no priority rules at all. "Unknown because we failed to capture it" never is.

```clojure
{:act/priority :not-applicable}   ; answered — considered, and did not arise
{:act/priority nil}               ; unanswered — nobody looked
{}                                ; unanswered — nobody looked
```

Implement it with `contains?` and the middle line starts passing silently. There is a test that catches exactly that.

---

## The act is not the envelope

Stream positions, expected versions, message ids, correlation and causation ids, schema versions and encoding metadata are all necessary. **None of them is part of the act.**

```clojure
{:envelope {:stream/id "DN12345" :stream/version 2 :schema/version 1 …}
 :act      registration-of-transfer}
```

> The envelope exists to make the software reliable. The act exists to make the institution intelligible.

A lawyer in a witness box does not care about stream position 2. Merge the two and within a release nobody can tell which keys are which — and the discipline that makes the thirteen questions useful is gone.

The invariant that keeps it honest: **every key of an act is in the `act` namespace.** An envelope key cannot be inside one without a test noticing.

---

## Two times, and the whole argument

The registration and the rectification differ on four things: a different act type, a different statutory power, an explicit link to the act being corrected, and this —

| | Took effect | Was recorded |
|---|---|---|
| `:registration-of-transfer` | 14 Mar 2019, 11:42:00 | 14 Mar 2019, 11:42:**03** |
| `:rectification-of-register` | 14 Mar **2019**, 11:42:00 | 19 Aug **2026**, 10:07 |

The rectification takes effect *exactly when the act it corrects did*. That is what separates **"the proprietor changed"** from **"we had the proprietor wrong"**, and no single timestamp can express it.

It is also why `:ground :clerical-error` is an enumerated value drawn from the regime and never prose. Had the ground been `:fraud`, the consequences and the appeal rights would differ.

---

## An act is a value

There is a deeper reason an act does not change, and it is not that we forbade editing.

Rich Hickey calls the alternative **place-oriented programming**: anytime new information replaces old information. An `UPDATE` is exactly that — a location reused, the previous occupant gone. Its origin is not a modelling decision but a hardware constraint. Memory and disk were scarce, so you overwrote the place. The constraint is gone; the habit stayed.

Read that way, immutability is not a rule imposed on the act record. **A value is not a place**, so there is nothing to overwrite. `assoc` returns a new map not because we forbade mutation but because that is what values do. New information does not replace old information — it accretes, which is precisely why a mistyped surname becomes a registration **and** a rectification rather than an edit.

```clojure
(assoc-in registration-of-transfer [:act/decision :class-of-title] :qualified)
;; => a new act; registration-of-transfer is untouched
```

Free in the language, and still a design commitment: nothing downstream may assume it can amend an act in place. That commitment is [C7](../README.md), and it is what makes the act record evidence of the registry's own guarantee rather than a log.

Identity, in this reading, is not a state — it *has* a state. Folio DN12345 is a stable logical entity associated with a series of different values over time, and the act record is the series. What CRUD stores is one of those values with the series thrown away.

---

## What this lab is worth, and what it isn't

Stated plainly, because a lab of six static maps can easily be a transcription exercise that feels productive.

**What it produces.** Two functions that outlive it. A conformance gate on act types 2 through N. A compile step for the worked examples, which are currently prose in a markdown file that nothing checks. And the discovery exercise itself — writing act 3 forces an answer to Q11 for a rectification, and the answer turns out to be `:not-applicable`, which is a domain finding rather than a formatting choice.

**What it does not produce.** Nothing architectural is proved: no store, no fold, no concurrency, no rebuild. And the model is not validated against the domain — the lab validates that we can write maps matching a document we wrote ourselves. Only a registrar reading the narration aloud does the other thing.

**The headline test is tautological.** `every-act-answers-all-thirteen` runs against hand-written literals containing all thirteen keys. It passes by construction and carries no information today; it earns its place on the day someone adds `:refusal-of-dealing` and has not thought about Q11. The test file marks which of the six are informative today and which are gates on the future, so nobody sees six green tests and infers six proofs.

Full reasoning, and the ladder this is the first rung of: [SPEC_PLAN_MECHANISM.md](SPEC_PLAN_MECHANISM.md).

---

## One thing deliberately left wrong

Enumerated values here are keywords — `:clerical-error`, `:registered`, `:party-lodged` — matching [Worked Examples](../README_EXAMPLES.md) exactly, so the document and the code are the same artefact.

That will not survive contact with a database. A keyword survives EDN and Transit and dies in JSONB: `:key-fn keyword` restores *keys*, because their names are known in advance, and there is no equivalent for values — nothing in the decoded data says which strings used to be symbols. Worse for this domain, [QA6](../README.md) promises that an analyst holding the preserved record and **no code** can determine what each act did. `:clerical-error` is a program symbol that means something to the code that wrote it and nothing to that analyst in 2041. `"clerical-error"`, drawn from the regime's controlled vocabulary, means the same thing to both.

The first lab with a store is where that bill is paid. Only `:act/type` survives as a keyword, in a column of its own, coerced once at the point of dispatch.

---

## What's next

Lab 1 is data. The pure core is three functions (**ADR-040**), and the difference between two of them carries the entire architecture:

```clojure
(defn evolve [state act]        …)  ;=> state'    no regime parameter
(defn decide [regime state cmd] …)  ;=> [act …]   regime passed explicitly
```

**`decide` takes a regime. `evolve` does not.** Reconstruction is `(reduce evolve initial-state acts)`, and it cannot drift as the law changes because there is no law inside it. That is only affordable because the act already carries the *judgement* rather than the material to recompute it — take Q12 off the act and `evolve` immediately needs a regime, at which point every amendment silently rewrites 2019.

Which is the answer to why this lab spends a disproportionate amount of effort on six maps. Lab 2 folds `acts` and gets the register back.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
