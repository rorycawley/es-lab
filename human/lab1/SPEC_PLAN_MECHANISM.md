# Lab 1 — Specification, Plan and Mechanism

*Status: **lab 1 is built.** 6 tests, 29 assertions, lint and format clean. The reasoning below is kept because sections 0 and 0.1 — what the lab is worth, and the ladder to a pure core — carry decisions the code cannot state. Sections 1–3 are now a record of what was decided rather than a plan.*

*One correction against the as-planned version: `unanswered` uses `some?`, not `contains?`. See [The two functions](#the-two-functions).*

Three sections, in the order they were asked for. **Specification** states what the lab must be true of — drawn from the Guiding Specification, not invented here. **Plan** is the model: the values, the fixture and the tests. **Mechanism** is how it gets built, and why each choice rather than the obvious alternative.

Identifiers cited throughout — **C**, **Q**, **QA**, **CHO**, **IMP**, **ADR** — are the permanent ones defined in [`../README.md`](../README.md) and [`../README_DECISIONS.md`](../README_DECISIONS.md).

---

## Where this lab sits

`human/` is a second track. The numbered sequence `lab0…lab34` teaches event sourcing concept by concept on an ice cream truck, where a modelling mistake is annoying. This track teaches the same discipline on a statutory registry, where a modelling mistake is **unrecoverable** — the information was never captured, and no amount of later work recovers it.

The two tracks ask the same first question in different registers.

| | Root `lab1` | `human/lab1` |
|---|---|---|
| Asks | What is a domain event? | What makes something an **act** rather than a record of a data change? |
| Filter | "Do you care that this happened?" | "Was a statutory power exercised?" (**Q6**) |
| Cost of getting the name wrong | A distinction is lost | A distinction is lost, and it was the evidence of our guarantee |

---

# 0 — What this lab is worth

Stated first, and stated with its limits, because a lab of six static maps can easily be a transcription exercise that feels productive. Cost is roughly 150 lines and a README — half a day. This is what that half day buys, and what it does not.

## What it produces

**1 — Two functions that outlive the lab.** `unanswered` and `act?`. Everything else in the lab is illustration; these are the deliverable.

`act?` is **Q6** in a form you can run:

```clojure
(act? notification-sent)   ;=> false
(act? official-search)     ;=> false
```

That is the filter of `../README.md:159` turned into a tool — the thing to point at a list of forty candidate event types when a workshop produces one, rather than adjudicating each by argument.

**2 — A conformance gate on act types 2 through N.** The specification's instruction (`../README_EASY.md:99`) is to resolve the thirteen questions for one act type *on paper, before writing code*. Paper does not fail. `unanswered` does, on the day someone adds `:refusal-of-dealing` and has not thought about Q11.

**3 — A compile step for the worked examples.** The act payloads in `../README_EXAMPLES.md` are prose inside a markdown file. Nothing checks them against the specification and nothing will notice when they drift — and they will, because the specification is a draft with ten open unknowns. In a namespace with a test, the document and the model cannot silently disagree.

**4 — The discovery exercise.** Writing act 3 forces an answer to Q11 for a rectification, and the answer turns out to be `:not-applicable`. That is a domain finding, not a formatting choice. **This is the largest of the four**, and the one most easily lost — see below.

## What it does not produce

**The headline test is tautological.** `every-act-answers-all-thirteen` runs against two hand-written literals written with all thirteen keys in them. It passes by construction and carries no information today. Its value is entirely prospective and realises only if a later lab reuses the fixture. The same applies, more weakly, to `the-non-acts-fail-q6`.

**Nothing architectural is proved.** No store, no append, no fold, no concurrency, no rebuild. `../README_EASY.md:101` is explicit that a complete vertical slice including a rectification is what proves the model. Six static maps are not that, and containing a rectification does not make them that.

**The model is not validated against the domain.** The lab validates that we can write maps matching a document we wrote ourselves. Only a registrar reading the narration aloud does the other thing, and that is what `../README_EXAMPLES.md:217` is for.

## What determines whether it is worth doing

Two conditions, both of which can fail quietly.

> **Copy the six values out of `../README_EXAMPLES.md` and lab 1 is a transcription.** Items 1 and 3 survive; item 4 evaporates — and item 4 is the largest. The value is in re-deriving each act from Q1–Q13 and noticing where the questions and the document disagree.

> **If there is no lab 2, item 2 evaporates too**, and what remains is a well-documented pair of functions.

So, plainly: lab 1 is worth building **as the first step of a sequence**, and it is a warm-up rather than a proof. Its output is a filter and a gate that the rest of the track leans on. If what is needed next is confidence that the architecture holds, this is not that lab — that is the vertical slice, and it needs a store.

The sequence it is the first step of is below.

---

# 0.1 — The road to a registry with a pure core

## The asymmetry that is the whole architecture

The domain core is three things (**ADR-040**), and the difference between two of them carries the entire design:

```clojure
(def initial-state  …)

(defn evolve [state act]          …)  ;=> state'     no regime parameter
(defn decide [regime state cmd]   …)  ;=> [act …]    regime passed explicitly
```

> **`decide` takes a regime. `evolve` does not.**

That single difference is **C9**. Reconstruction is `(reduce evolve initial-state acts)`, and it cannot drift as the law changes because there is no law inside it. Rules are for deciding new acts and explaining old ones — never for rebuilding history.

And it is only affordable because of **C6**: `evolve` can be rule-free only if the act already carries the judgement rather than the material to recompute it. `:class-of-title :absolute` is *read*, never derived. Take Q12 off the act and `evolve` immediately needs a regime, at which point every change in the law silently rewrites 2019.

**This is what lab 1 is for.** It is not a warm-up on the way to the core — it defines the type that flows through all of it. The act is the output of `decide`, the input of `evolve`, and the thing the store persists. The thirteen questions exist to guarantee `evolve` never has to ask one.

## The ladder

Seven labs. **The pure core is complete at lab 4** — in memory, no Docker, no database. Everything after it is shell.

| Lab | Adds | Pure? | Discharges |
|---|---|---|---|
| **1** | The act. `act?`, `unanswered`, Q1–Q13 | data only | C1, C3, C5, C6 |
| **2** | `evolve` — fold acts into a folio view | ✅ | **C9** |
| **3** | `as-at` — the two cut-offs | ✅ | **C2, C8** |
| **4** | `decide` — rules enter, and only here | ✅ | C3, ADR-041 |
| — | *the core is done; everything below is I/O* | | **CHO7** |
| **5** | The shell — append and load, in-memory store | ❌ | ADR-043 |
| **6** | PostgreSQL — concurrency, projection in-transaction | ❌ | IMP1, ADR-022, ADR-030 |
| **7** | Store separation — act record vs ledger vs audit | ❌ | **ADR-006** |

### Lab 2 — `evolve`

Fold lab 1's two acts into a view of DN12345. The demonstration is that the rectification folds through the *same function*, with no rules, and the proprietor's name changes. Nothing re-decides; `evolve` reads what act 3 already recorded.

### Lab 3 — `as-at`

Filter, then fold. Five lines, and it is **C8**:

```clojure
(defn as-at [acts {:keys [effect-time record-time]}]
  (->> acts
       (filter #(and (not (pos? (compare (:act/recorded-at %)  record-time)))
                     (not (pos? (compare (:act/effective-at %) effect-time)))))
       (reduce evolve initial-state)))
```

Two cut-offs, no rules, and it produces the answer table at `../README_EXAMPLES.md:121` — including the row that distinguishes *who owned it on 1 Jan 2020* from *what we asserted on 1 Jan 2020*. This is the lab that proves the model in the way lab 1 cannot, **and it is still pure and in-memory**.

### Lab 4 — `decide`

Rules arrive, confined to one function. The regime is a parameter, never an ambient lookup (**CHO2**), so an amendment can be simulated before commencement. A refusal returns an act; it does not throw (**ADR-041**), because a refusal is an exercise of statutory power and appealable.

At this point the whole domain is testable with no infrastructure — which is what **CHO7** was traded for.

### Labs 5–7 — the shell

Only here does anything perform I/O. Lab 5 establishes the boundary — the shell loads acts and assembles cross-aggregate evidence, passes it as a value, and calls the pure core (**ADR-043**). Lab 6 makes it PostgreSQL and pays the bill on the keyword decision recorded below. Lab 7 splits the stores, which is the decision `../README_DECISIONS.md:145` says must come first and which the earlier labs can defer only because they store nothing.

**The vertical slice of `../README_EASY.md:101`** — command through to a historical query on both axes, plus a replay, including a rectification — is complete at lab 6.

## What lab 1 must get right, and what breaks if it doesn't

The asymmetry of `../README_EASY.md:49` made concrete: each of these is cheap now and unrecoverable later, because the information would never have been captured.

| Lab 1 must carry | Or this breaks |
|---|---|
| **Q12** — the decision, not the inputs | Lab 2. `evolve` needs a regime, and C9 is gone |
| **Q9 and Q10** as separate keys | Lab 3. `as-at` has one axis to filter on and can answer only half the questions |
| **Q7** — regime bound to the act | Lab 4. An act can be replayed but never explained |
| **The envelope kept out of the act** | Lab 6. The store cannot add `:stream/version` without changing `evolve`'s input type |
| **`act?` / all thirteen resolved** | Lab 7. Non-acts reach the fold, and the filter becomes a correctness dependency for every historical answer |

Every row is a later lab failing for a reason introduced here. That is the argument for spending a disproportionate half-day on six maps.

---

# 1 — Specification

## What the lab is

The smallest thing that is recognisably an act, plus the test that decides whether something is one. Static example data and assertions over it. **No store, no I/O, no behaviour.**

## What it must make executable

The Guiding Specification states these as prose. Lab 1's job is to turn them into something that fails.

| # | The claim | Source |
|---|---|---|
| 1 | Every act explicitly resolves all thirteen questions. "Not applicable" is a valid answer; "unknown because we failed to capture it" never is | `../README.md:98` |
| 2 | No statutory power exercised, no act — **Q6 is the filter** | `../README.md:128` |
| 3 | The act is not the envelope. Stream positions, schema versions and correlation ids are necessary and none of them is part of the act | `../README.md:118-124` |
| 4 | Effect time and record time are two axes, and they come apart | **C2** |
| 5 | An act is a value. There is nothing to overwrite | **C7**, **CHO1** |

## The acceptance test

The lab's own version of `../README.md:139`:

> Could this record be read aloud in court as an account of what the registry did and why it was entitled to do it?

If a value in `registry.act` cannot survive that question, it is in the file as a **counter-example** and is labelled as one.

## Commitments exercised

| Commitment | Where it shows in the lab |
|---|---|
| **C1** — the act is the fact | `folio-row` is what we do *not* keep; the act is |
| **C2** — two time axes | the rectification, effect 2019 / record 2026 |
| **C3** — authority, actor, delegation, instance, evidence | Q3–Q8, present on both acts and absent from all three non-acts |
| **C5** — regime bound to the act | act 2 carries `ie-land-2019.01.14`, act 3 carries `ie-land-2026.07.01` |
| **C6** — decision recorded, not inputs | `:act/decision {:outcome :registered :class-of-title :absolute}` |
| **C7** — never altered | the immutability test; `assoc` returns a new map |
| **QA6** — interpretability without the system | the enumerated-values note (see [Decisions taken](#decisions-taken)) |

## Deliberately not in this lab

Naming these keeps the scope honest and stops the lab growing into lab 4.

| Absent | Belongs to |
|---|---|
| Appending, a store, PostgreSQL | **IMP1** — a later lab |
| Folding acts into a view | reconstruction — **C9** |
| `decide` / `evolve` / `initial-state` | **ADR-040** |
| Projections, bitemporal queries | **C2**, **C8**, **ADR-032** |
| Rule regimes as executable data | **ADR-042**, **NG4** |
| Malli, spec, or any schema library | see [Mechanism](#why-a-map-and-not-malli) |

---

# 2 — Plan

## The model

Six values, in this order, each a `def` with no behaviour. The sequence is the argument: start with what a CRUD system would keep, and add only what is missing.

### 1. What CRUD would store

The starting point, and the thing that has already lost the argument. It is true, and it is not evidence.

```clojure
(def folio-row
  {:folio          "DN12345"
   :proprietor     "Máire Ní Bhriain"
   :class-of-title "absolute"})
```

### 2. The too-fine failure

A field change. Fails **Q6**: there is no statutory power to change a proprietor's name in isolation. The act was a registration of transfer, and decomposing it evaporates the legal meaning (`../README.md:132`).

```clojure
(def field-change
  {:changed :proprietor/name
   :from    "Maire Ni Bhrian"
   :to      "Máire Ní Bhriain"})
```

### 3. The act

Case 1, act 2 from [`../README_EXAMPLES.md`](../README_EXAMPLES.md), verbatim, each key annotated with the question it answers.

```clojure
(def registration-of-transfer
  {:act/type         :registration-of-transfer                          ; Q1
   :act/subjects     [{:folio "DN12345"}]                               ; Q2
   :act/actor        {:id "REG-0042"}                                   ; Q3
   :act/delegation   "DEL-2018-004"                                     ; Q4
   :act/instance     :party-lodged                                      ; Q5
   :act/power        {:instrument "Registration of Title Act 1964"
                      :provision  "s.[x]"}                              ; Q6
   :act/regime       "ie-land-2019.01.14"                               ; Q7
   :act/basis        [{:type :instrument :kind :transfer
                       :doc/id "DOC-99187" :doc/hash "sha256:9c1f…"}]   ; Q8
   :act/effective-at #inst "2019-03-14T11:42:00.000-00:00"              ; Q9
   :act/recorded-at  #inst "2019-03-14T11:42:03.000-00:00"              ; Q10
   :act/priority     {:from-lodgement "2019/004120"
                      :lodged-at #inst "2019-02-27T09:15:00.000-00:00"} ; Q11
   :act/decision     {:outcome :registered
                      :class-of-title :absolute}                        ; Q12
   :act/effect       {:proprietor/from [{:party/id "P-3341"}]
                      :proprietor/to   [{:party/id "P-8802"
                                         :filed-as "Máire Ní Bhriain"}]}}) ; Q13
```

`:filed-as` is the load-bearing detail. The deed said *Máire Ní Bhriain*; the register said *Maire Ni Bhrian*. The discrepancy is visible in the record from the moment it is made, which is how the error is found seven years later and how the ground for rectification is established.

### 4. Three things that are not acts

One per store, from the borderline table (`../README.md:167-182`). Each visibly missing `:act/power`, which is the entire point.

| Value | Store | Why not an act |
|---|---|---|
| `notification-sent` | Operational | The legal consequence already occurred |
| `official-search` | Assertion ledger | The registry reported; it decided nothing |
| `regime-deployed` | Audit | Authority over the *system*, not over the register |

The official search is the interesting one: neither an act nor operational, and the one artefact a two-store model loses. It is the only proof of what the purchaser was actually shown — **C10**.

### 5. The act is not the envelope

A two-key wrapper. Not a merge, and that is the lesson.

```clojure
(def registration-in-a-stream
  {:envelope {:stream/id      "DN12345"
              :stream/version 2
              :schema/version 1
              :correlation/id #uuid "…"}
   :act      registration-of-transfer})
```

> The envelope exists to make the software reliable. The act exists to make the institution intelligible.

Merge them and the discipline is gone within a release: a lawyer in a witness box does not care about stream position 2, and once machinery is sitting among the thirteen answers nobody can tell which keys are which.

### 6. The rectification

Case 1, act 3. One more map, and the only thing in the lab that proves two time axes exist.

```clojure
(def rectification-of-register
  {:act/type         :rectification-of-register                ; Q1  — different act type
   :act/subjects     [{:folio "DN12345"}]
   :act/actor        {:id "REG-0011"}
   :act/delegation   "DEL-2025-019"                            ; Q4  — senior delegation
   :act/instance     :registrar-own-motion                     ; Q5  — nobody filed
   :act/power        {:instrument "Registration of Title Act 1964"
                      :provision  "s.[y]"}                     ; Q6  — different power
   :act/regime       "ie-land-2026.07.01"                      ; Q7  — today's regime
   :act/basis        [{:type :instrument :doc/id "DOC-99187"
                       :doc/hash "sha256:9c1f…"}
                      {:type :internal-report :ref "ERR-2026-0881"}]
   :act/corrects     {:act/id "…act-2…"}
   :act/effective-at #inst "2019-03-14T11:42:00.000-00:00"     ; Q9  — BACKDATED
   :act/recorded-at  #inst "2026-08-19T10:07:00.000-00:00"     ; Q10 — today
   :act/priority     :not-applicable                           ; Q11
   :act/decision     {:outcome :rectified
                      :ground  :clerical-error                 ; Q12 — enumerated, never prose
                      :consent-of-affected-parties true}
   :act/effect       {:corrected-field  :proprietor/name
                      :as-recorded      "Maire Ni Bhrian"
                      :should-have-read "Máire Ní Bhriain"}})
```

Four differences from act 3 carry the whole argument: a different act type, a different statutory power, an effect time seven years before the record time, and an explicit link to the act being corrected.

Note `:act/priority :not-applicable`. A rectification fixes no priority — and the lab needs one value that answers a question with "not applicable" so the fixture below has something real to distinguish from a missing key.

## The two functions

**The point of the lab.** The six values above are examples of what these two say; they are not themselves the output. The README should say so in its first paragraph, so nobody reads the lab as a pile of maps.

```clojure
(defn act?
  "Q6 as a predicate. Was a statutory power exercised?

   The filter of ../README.md:159, in a form you can point at a candidate
   rather than adjudicate by argument. Deliberately the weakest possible
   check: presence of :act/power. It cannot tell you the provision is the
   right one — only that somebody was made to name one."
  [candidate]
  (contains? candidate :act/power))

(def questions
  "Q1–Q13 mapped to the act key that answers each.
   The schema is derived from the questions, not designed."
  {:q1  :act/type       :q2  :act/subjects   :q3  :act/actor
   :q4  :act/delegation :q5  :act/instance   :q6  :act/power
   :q7  :act/regime     :q8  :act/basis      :q9  :act/effective-at
   :q10 :act/recorded-at :q11 :act/priority  :q12 :act/decision
   :q13 :act/effect})

(defn unanswered
  "The questions this act leaves unresolved.

   A key holding :not-applicable is answered — a regime may genuinely not
   apply. A key that is absent, or present holding nil, is not: that is
   'unknown because we failed to capture it', which ../README.md:98 says is
   never a valid answer."
  [act]
  (into (sorted-set)
        (remove #(some? (get act (questions %))))
        (keys questions)))
```

**`some?`, not `contains?`.** An earlier draft of this document specified `contains?`, which is wrong: it treats `{:act/priority nil}` as answered. Three states, and they are not two —

```clojure
{:act/priority :not-applicable}   ; answered — considered, and did not arise
{:act/priority nil}               ; unanswered — nobody looked
{}                                ; unanswered — nobody looked
```

That one-word choice is `../README.md:98` made executable, and it is worth a paragraph in the README. `not-applicable-is-an-answer-nil-is-not` exists to catch a revert to `contains?`; it has been confirmed to fail against one.

## The tests

Six, one per property. No generators, no Docker, no I/O.

The third column is the honest one. Two of these pass by construction and are gates on future act types rather than checks on this one; see [What this lab is worth](#0--what-this-lab-is-worth). Marking them in the file itself stops a later reader mistaking a placeholder for a proof.

| Test | Asserts | Informative today? |
|---|---|---|
| `every-act-answers-all-thirteen` | `unanswered` is empty for both acts | No — gate on act types 2..N |
| `not-applicable-is-an-answer-nil-is-not` | a `:not-applicable` value passes; an explicit `nil` fails | **Yes** — pins `some?` over `contains?` |
| `the-non-acts-fail-q6` | `act?` is false for all three | Weakly — the predicate is the artefact |
| `the-act-carries-no-envelope-keys` | no `:stream/*`, `:schema/*` or `:correlation/*` key inside `:act` | **Yes** — fails on the first merge |
| `two-time-axes-come-apart` | the rectification's effect time precedes its record time by seven years; the registration's differ by three seconds | **Yes** — fails if either is dropped |
| `an-act-is-a-value` | `assoc` returns a new map and the original is untouched | No — a property of Clojure, kept as documentation |

## Files

```
human/lab1/
  README.md                     the lab itself — replaces the current seed notes
  SPEC_PLAN_MECHANISM.md        this document
  mise.toml                     copied verbatim from ../../lab1
  deps.edn                      copied verbatim
  bb.edn                        copied verbatim
  tests.edn                     copied verbatim
  src/registry/act.clj
  test/registry/act_test.clj
```

The existing `README.md` — Hickey on values, identity and state, place-oriented programming — is not discarded. It becomes the lab's *why immutable* section, which is where it was always heading:

> An act does not change because we forbade editing it. A value is not a place, so there is nothing to overwrite. `assoc` returns a new map not because we imposed a rule but because that is what values do.

Which is exactly why a mistaken entry becomes a registration **and** a rectification rather than an edit — and why **C7** can promise that the act record is the evidence of the registry's own guarantee.

---

# 3 — Mechanism

## Choices

| Concern | Mechanism | Serves |
|---|---|---|
| Language | Clojure, pure data, no I/O anywhere | **ADR-004**, **CHO7**, **CHO9** |
| Act representation | Plain maps, namespaced keys, no records or protocols | **ADR-010**, **CHO4**, **QA6** |
| Q1–Q13 | A `questions` map and an `unanswered` fn — not a schema library | see below |
| Q6 | An `act?` predicate, exported as the lab's output alongside `unanswered` | **C1**, `../README.md:159` |
| Envelope | A separate top-level key, never merged into the act | **ADR-012** |
| Enumerated values | Keywords, matching `../README_EXAMPLES.md` exactly | see [Decisions taken](#decisions-taken) |
| Tests | `clojure.test` via kaocha | matches every other lab |
| Namespace | `registry.act` at `src/registry/act.clj` | see below |
| Tooling | The four config files copied verbatim from `../../lab1` | one toolchain across the repo |
| Commands | `bb all`, `bb test` — identical to every other lab | — |

Pinned versions, inherited unchanged from `../../lab1`: temurin-25, Clojure 1.12.5, babashka 1.13.219, clj-kondo 2026.08.04, cljfmt 0.16.5, kaocha 1.91.1392. These are already ahead of the archived projects; do not assume they match.

## Why a map and not Malli

A schema says *these keys are required*. The `questions` map says *which legal question each key answers* — and that is the whole of `../README.md:96`:

> The schema is derived from them, not designed.

A validation library gives you the constraint and silently discards the reason for it. Six months later someone deletes a required key because "nothing reads it", and the thing they actually deleted was Q4 — the answer to whether *this officer* was entitled to exercise the power, which is the question that decides whether the act was void.

Malli earns its place when there is a wire format to validate. That is several labs away and belongs with the store.

## Why `registry.act` and not `lab1.act`

The repository root already owns `lab1.event`. Two `lab1` namespace roots in one editor workspace is precisely the confusion that `.clj-kondo/config.edn` and `.lsp/config.edn` were written to explain away — both files document at length that this repository is not one project but forty-odd independent ones, and that clojure-lsp resolves them as though it were one.

Naming this track `registry.*` keeps them distinguishable. Each subsequent human lab takes its own namespace under that root, named for its subject rather than its number.

## Repository wiring

Two things that will not work by default, both worth fixing before the lab is written rather than after.

**`human/` is invisible to the root tasks.** The `labs` fn in `../../bb.edn` globs `lab*` in `.`, so neither `bb audit` nor `bb test-all` will ever see `human/lab1`. Extend it to include `human/lab*`. A lab nobody runs rots, and this one is a test suite whose entire value is that it fails when the model drifts.

**`audit.clj` checks rules written for the numbered sequence.** `lab-dirs` feeds the README consistency checks — the labs table, the "What's next" chain, the container prerequisites. Widening it without thought will report the human track as broken against rules it was never meant to satisfy. Either widen `lab-dirs` and give the human track its own checks, or leave `audit` alone and wire only `test-all`.

## Decisions taken

Both were genuine forks. Recorded here so they are not silently reopened.

### Enumerated values stay keywords

`:ground :clerical-error`, `:outcome :registered`, `:act/instance :party-lodged` — identical to `../README_EXAMPLES.md`.

**Why.** Lab 1 persists nothing, so the repository-wide rule that a stored fact is data rather than a program symbol does not bite yet. Keeping the lab byte-identical to the worked example matters more at lab 1 than anticipating a constraint from lab 12: the document and the code should be the same artefact, and a reader who spots a difference will assume one of them is wrong.

**What it costs, and where the README must say so.** A keyword survives EDN and Transit and dies in JSONB. `:key-fn keyword` restores *keys*, because their names are known in advance; there is no equivalent for values, and nothing in the decoded data says which strings used to be symbols. Worse for this domain: **QA6** promises that an analyst holding the preservation unit and no code can determine what each act did. `:clerical-error` is a Clojure symbol that means something to the program that wrote it and nothing to that analyst in 2041. `"clerical-error"` drawn from the regime's controlled vocabulary means the same thing to both.

So the README carries one paragraph naming the cost, and the first lab that writes an act to PostgreSQL is where it gets paid. Only `:act/type` survives as a keyword, in a column of its own, coerced once at the point of dispatch.

### The lab holds all six values

Including the envelope split and the rectification, rather than deferring either to lab 2.

**Why.** Both are one extra map. The rectification is the only thing in the lab that demonstrates **C2**, and the specification is blunt about the general form of this mistake (`../README_EASY.md:91`):

> Proving the model on the easy case. Registration works in any architecture. If your first vertical slice doesn't include a rectification, you have proved nothing.

A lab that shows only a registration teaches a shape that any CRUD system also produces.

---

## Open, and deliberately so

| Question | Blocked on |
|---|---|
| Whether Case 2 (deemed cessation) enters the ladder, and at which lab | Case 2 reaches **U9** |
| Whether the state `evolve` builds is a folio view or something narrower | lab 2, and **ADR-044** |
| Whether labs 5–7 collapse into one | how much of lab 6 is PostgreSQL mechanics rather than model |
| Whether the human track gets its own `audit.clj` checks | how many human labs there turn out to be |
| Where enumerated values become strings | lab 6 — the first lab with a store |
